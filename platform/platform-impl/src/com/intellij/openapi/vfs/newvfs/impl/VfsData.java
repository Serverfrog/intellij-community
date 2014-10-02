/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vfs.newvfs.impl;

import com.intellij.openapi.application.ApplicationAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.InvalidVirtualFileAccessException;
import com.intellij.util.ArrayUtil;
import com.intellij.util.SmartFMap;
import com.intellij.util.concurrency.AtomicFieldUpdater;
import com.intellij.util.containers.ConcurrentBitSet;
import com.intellij.util.containers.ConcurrentIntObjectMap;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.keyFMap.KeyFMap;
import com.intellij.util.text.CaseInsensitiveStringHashingStrategy;
import gnu.trove.THashSet;
import gnu.trove.TIntHashSet;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry.ALL_FLAGS_MASK;
import static com.intellij.util.ObjectUtils.assertNotNull;

/**
 * The place where all the data is stored for VFS parts loaded into a memory: name-ids, flags, user data, children.
 *
 * The purpose is to avoid holding this data in separate immortal file/directory objects because that involves space overhead, significant
 * when there are hundreds of thousands of files.
 *
 * The data is stored per-id in blocks of {@link #SEGMENT_SIZE}. File ids in one project tend to cluster together,
 * so the overhead for non-loaded id should not be large in most cases.
 *
 * File objects are still created if needed. There might be several objects for the same file, so equals() should be used instead of ==.
 *
 * The lifecycle of a file object is as follows:
 *
 * 1. The file has not been instantiated yet, so {@link #getFileById} returns null.
 *
 * 2. A file is explicitly requested by calling getChildren or findChild on its parent. The parent initializes all the necessary data (in a thread-safe context)
 * and creates the file instance. See {@link #initFile}
 *
 * 3. After that the file is live, an object representing it can be retrieved any time from its parent. File system roots are
 * kept on hard references in {@link com.intellij.openapi.vfs.newvfs.persistent.PersistentFS}
 *
 * 4. If a file is deleted (invalidated), then its data is not needed anymore, and should be removed. But this can only happen after
 * all the listener have been notified about the file deletion and have had their chance to look at the data the last time. See {@link #killInvalidatedFiles()}
 *
 * 5. The file with removed data is marked as "dead" (see {@link #ourDeadMarker}, any access to it will throw {@link com.intellij.openapi.vfs.InvalidVirtualFileAccessException}
 * Dead ids won't be reused in the same session of the IDE.
 *
 * @author peter
 */
public class VfsData {
  private static final int SEGMENT_BITS = 9;
  private static final int SEGMENT_SIZE = 1 << SEGMENT_BITS;
  private static final int OFFSET_MASK = SEGMENT_SIZE - 1;
  private static final Object ourDeadMarker = new String("dead file");

  private static final ConcurrentIntObjectMap<Segment> ourSegments = ContainerUtil.createConcurrentIntObjectMap();
  private static final ConcurrentBitSet ourInvalidatedIds = new ConcurrentBitSet();
  private static TIntHashSet ourDyingIds = new TIntHashSet();
  private static volatile SmartFMap<VirtualFileSystemEntry, VirtualDirectoryImpl> ourChangedParents = SmartFMap.emptyMap();

  static {
    ApplicationManager.getApplication().addApplicationListener(new ApplicationAdapter() {
      @Override
      public void writeActionFinished(Object action) {
        // after top-level write action is finished, all the deletion listeners should have processed the deleted files
        // and their data is considered safe to remove. From this point on accessing a removed file will result in an exception.
        if (!ApplicationManager.getApplication().isWriteAccessAllowed()) {
          killInvalidatedFiles();
        }
      }
    });
  }

  private static void killInvalidatedFiles() {
    synchronized (ourDeadMarker) {
      if (!ourDyingIds.isEmpty()) {
        for (int id : ourDyingIds.toArray()) {
          assertNotNull(getSegment(id, false)).myObjectArray.set(getOffset(id), ourDeadMarker);
          ourChangedParents = ourChangedParents.minus(new VirtualFileImpl(id, null, null));
        }
        ourDyingIds = new TIntHashSet();
      }
    }
  }

  @Nullable
  public static VirtualFileSystemEntry getFileById(int id, VirtualDirectoryImpl parent) {
    Segment segment = getSegment(id, false);
    if (segment == null) return null;

    int offset = getOffset(id);
    Object o = segment.myObjectArray.get(offset);
    if (o == null) return null;

    if (o == ourDeadMarker) {
      throw reportDeadFileAccess(new VirtualFileImpl(id, segment, parent));
    }
    assert segment.getNameId(id) > 0;

    return o instanceof DirectoryData ? new VirtualDirectoryImpl(id, segment, (DirectoryData)o, parent, parent.getFileSystem())
                                      : new VirtualFileImpl(id, segment, parent);
  }

  private static InvalidVirtualFileAccessException reportDeadFileAccess(VirtualFileSystemEntry file) {
    return new InvalidVirtualFileAccessException("Accessing dead virtual file: " + file.getUrl());
  }

  private static int getOffset(int id) {
    return id & OFFSET_MASK;
  }

  @Nullable @Contract("_,true->!null")
  public static Segment getSegment(int id, boolean create) {
    int key = id >>> SEGMENT_BITS;
    Segment segment = ourSegments.get(key);
    if (segment != null || !create) return segment;
    return ourSegments.cacheOrGet(key, new Segment());
  }

  public static void initFile(int id, Segment segment, int nameId, @NotNull Object data) {
    assert id > 0;
    int offset = getOffset(id);

    segment.setNameId(id, nameId);

    if (segment.myObjectArray.get(offset) != null) {
      throw new AssertionError("File already created");
    }
    segment.myObjectArray.set(offset, data);
  }

  static CharSequence getNameByFileId(int id) {
    return FileNameCache.getVFileName(assertNotNull(getSegment(id, false)).getNameId(id));
  }

  static boolean isFileValid(int id) {
    return !ourInvalidatedIds.get(id);
  }

  @Nullable
  static VirtualDirectoryImpl getChangedParent(VirtualFileSystemEntry child) {
    SmartFMap<VirtualFileSystemEntry, VirtualDirectoryImpl> map = ourChangedParents;
    return map == (SmartFMap)SmartFMap.emptyMap() ? null : map.get(child);
  }

  static void changeParent(VirtualFileSystemEntry child, VirtualDirectoryImpl parent) {
    synchronized (ourDeadMarker) {
      ourChangedParents = ourChangedParents.plus(child, parent);
    }
  }

  static void invalidateFile(int id) {
    ourInvalidatedIds.set(id);
    synchronized (ourDeadMarker) {
      ourDyingIds.add(id);
    }
  }

  public static class Segment {
    // user data for files, DirectoryData for folders
    final AtomicReferenceArray<Object> myObjectArray = new AtomicReferenceArray<Object>(SEGMENT_SIZE);

    // <nameId, flags> pairs, "flags" part containing flags per se and modification stamp
    private final AtomicIntegerArray myIntArray = new AtomicIntegerArray(SEGMENT_SIZE * 2);

    int getNameId(int fileId) {
      return myIntArray.get(getOffset(fileId) * 2);
    }

    void setNameId(int fileId, int nameId) {
      myIntArray.set(getOffset(fileId) * 2, nameId);
    }

    void setUserMap(int fileId, KeyFMap map) {
      myObjectArray.set(getOffset(fileId), map);
    }

    KeyFMap getUserMap(VirtualFileSystemEntry file) {
      Object o = myObjectArray.get(getOffset(Math.abs(file.getId())));
      if (!(o instanceof KeyFMap)) {
        throw reportDeadFileAccess(file);
      }
      return (KeyFMap)o;
    }

    boolean changeUserMap(int fileId, KeyFMap oldMap, KeyFMap newMap) {
      return myObjectArray.compareAndSet(getOffset(fileId), oldMap, newMap);
    }

    boolean getFlag(int id, int mask) {
      assert (mask & ~ALL_FLAGS_MASK) == 0 : "Unexpected flag";
      return (myIntArray.get(getOffset(id) * 2 + 1) & mask) != 0;
    }

    void setFlag(int id, int mask, boolean value) {
      assert (mask & ~ALL_FLAGS_MASK) == 0 : "Unexpected flag";
      int offset = getOffset(id) * 2 + 1;
      while (true) {
        int oldInt = myIntArray.get(offset);
        int updated = value ? (oldInt | mask) : (oldInt & ~mask);
        if (myIntArray.compareAndSet(offset, oldInt, updated)) {
          return;
        }
      }
    }

    long getModificationStamp(int id) {
      return myIntArray.get(getOffset(id) * 2 + 1) & ~ALL_FLAGS_MASK;
    }

    void setModificationStamp(int id, long stamp) {
      int offset = getOffset(id) * 2 + 1;
      while (true) {
        int oldInt = myIntArray.get(offset);
        int updated = (oldInt & ALL_FLAGS_MASK) | ((int)stamp & ~ALL_FLAGS_MASK);
        if (myIntArray.compareAndSet(offset, oldInt, updated)) {
          return;
        }
      }
    }

  }

  // non-final field accesses are synchronized on this instance, but this happens in VirtualDirectoryImpl
  public static class DirectoryData {
    private static final AtomicFieldUpdater<DirectoryData, KeyFMap> updater = AtomicFieldUpdater.forFieldOfType(DirectoryData.class, KeyFMap.class);
    volatile KeyFMap myUserMap = KeyFMap.EMPTY_MAP;
    int[] myChildrenIds = ArrayUtil.EMPTY_INT_ARRAY;
    private THashSet<String> myAdoptedNames;

    VirtualFileSystemEntry[] getFileChildren(int fileId, VirtualDirectoryImpl parent) {
      assert fileId > 0;
      VirtualFileSystemEntry[] children = new VirtualFileSystemEntry[myChildrenIds.length];
      for (int i = 0; i < myChildrenIds.length; i++) {
        children[i] = assertNotNull(getFileById(myChildrenIds[i], parent));
      }
      return children;
    }

    boolean changeUserMap(KeyFMap oldMap, KeyFMap newMap) {
      return updater.compareAndSet(this, oldMap, newMap);
    }

    boolean isAdoptedName(String name) {
      return myAdoptedNames != null && myAdoptedNames.contains(name);
    }

    void removeAdoptedName(String name) {
      if (myAdoptedNames != null) {
        myAdoptedNames.remove(name);
        if (myAdoptedNames.isEmpty()) {
          myAdoptedNames = null;
        }
      }
    }
    void addAdoptedName(String name, boolean caseSensitive) {
      if (myAdoptedNames == null) {
        //noinspection unchecked
        myAdoptedNames = new THashSet<String>(0, caseSensitive ? TObjectHashingStrategy.CANONICAL : CaseInsensitiveStringHashingStrategy.INSTANCE);
      }
      myAdoptedNames.add(name);
    }

    List<String> getAdoptedNames() {
      return myAdoptedNames == null ? Collections.<String>emptyList() : ContainerUtil.newArrayList(myAdoptedNames);
    }
  }

}
