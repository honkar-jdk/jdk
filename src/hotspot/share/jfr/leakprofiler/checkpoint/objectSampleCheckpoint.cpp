/*
 * Copyright (c) 2014, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "jfr/jfrEvents.hpp"
#include "jfr/jni/jfrJavaSupport.hpp"
#include "jfr/leakprofiler/chains/edgeStore.hpp"
#include "jfr/leakprofiler/chains/objectSampleMarker.hpp"
#include "jfr/leakprofiler/checkpoint/objectSampleCheckpoint.hpp"
#include "jfr/leakprofiler/checkpoint/objectSampleWriter.hpp"
#include "jfr/leakprofiler/leakProfiler.hpp"
#include "jfr/leakprofiler/sampling/objectSample.hpp"
#include "jfr/leakprofiler/sampling/objectSampler.hpp"
#include "jfr/recorder/checkpoint/jfrCheckpointWriter.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceId.inline.hpp"
#include "jfr/recorder/service/jfrOptionSet.hpp"
#include "jfr/recorder/stacktrace/jfrStackTraceRepository.inline.hpp"
#include "jfr/recorder/storage/jfrReferenceCountedStorage.hpp"
#include "jfr/support/jfrKlassUnloading.hpp"
#include "jfr/support/jfrMethodLookup.hpp"
#include "jfr/utilities/jfrHashtable.hpp"
#include "jfr/utilities/jfrSet.hpp"
#include "jfr/utilities/jfrRelation.hpp"
#include "memory/resourceArea.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/safepoint.hpp"

const unsigned int initial_size = 431;

static JfrCHeapTraceIdSet* c_heap_allocate_set(int size = initial_size) {
  return new JfrCHeapTraceIdSet(size);
}

static JfrCHeapTraceIdSet* unloaded_thread_id_set = nullptr;

class ThreadIdExclusiveAccess : public StackObj {
 private:
  static Semaphore _mutex_semaphore;
 public:
  ThreadIdExclusiveAccess() { _mutex_semaphore.wait(); }
  ~ThreadIdExclusiveAccess() { _mutex_semaphore.signal(); }
};

Semaphore ThreadIdExclusiveAccess::_mutex_semaphore(1);

static bool has_thread_exited(traceid tid) {
  assert(tid != 0, "invariant");
  if (unloaded_thread_id_set == nullptr) {
    return false;
  }
  ThreadIdExclusiveAccess lock;
  return unloaded_thread_id_set->contains(tid);
}

static void add_to_unloaded_thread_set(traceid tid) {
  ThreadIdExclusiveAccess lock;
  if (unloaded_thread_id_set == nullptr) {
    unloaded_thread_id_set = c_heap_allocate_set();
  }
  unloaded_thread_id_set->add(tid);
}

void ObjectSampleCheckpoint::on_thread_exit(traceid tid) {
  assert(tid != 0, "invariant");
  if (LeakProfiler::is_running()) {
    add_to_unloaded_thread_set(tid);
  }
}

void ObjectSampleCheckpoint::clear() {
  assert(SafepointSynchronize::is_at_safepoint(), "invariant");
  if (unloaded_thread_id_set != nullptr) {
    delete unloaded_thread_id_set;
    unloaded_thread_id_set = nullptr;
  }
  assert(unloaded_thread_id_set == nullptr, "invariant");
}

template <typename Processor>
static void do_samples(ObjectSample* sample, const ObjectSample* end, Processor& processor) {
  assert(sample != nullptr, "invariant");
  while (sample != end) {
    processor.sample_do(sample);
    sample = sample->next();
  }
}

template <typename Processor>
static void iterate_samples(Processor& processor, bool all = false) {
  ObjectSampler* const sampler = ObjectSampler::sampler();
  assert(sampler != nullptr, "invariant");
  ObjectSample* const last = sampler->last();
  assert(last != nullptr, "invariant");
  do_samples(last, all ? nullptr : sampler->last_resolved(), processor);
}

class SampleMarker {
 private:
  ObjectSampleMarker& _marker;
  jlong _last_sweep;
  int _count;
 public:
  SampleMarker(ObjectSampleMarker& marker, jlong last_sweep) : _marker(marker), _last_sweep(last_sweep), _count(0) {}
  void sample_do(ObjectSample* sample) {
    if (sample->is_alive_and_older_than(_last_sweep)) {
      _marker.mark(sample->object());
      ++_count;
    }
  }
  int count() const {
    return _count;
  }
};

int ObjectSampleCheckpoint::save_mark_words(const ObjectSampler* sampler, ObjectSampleMarker& marker, bool emit_all) {
  assert(sampler != nullptr, "invariant");
  if (sampler->last() == nullptr) {
    return 0;
  }
  SampleMarker sample_marker(marker, emit_all ? max_jlong : ObjectSampler::last_sweep());
  iterate_samples(sample_marker, true);
  return sample_marker.count();
}

class BlobCache {
  typedef HashTableHost<JfrBlobHandle, traceid, JfrHashtableEntry, BlobCache> BlobTable;
  typedef BlobTable::HashEntry BlobEntry;
 private:
  BlobTable _table;
  traceid _lookup_id;
 public:
  BlobCache(size_t size) : _table(this, size), _lookup_id(0) {}
  JfrBlobHandle get(const ObjectSample* sample);
  void put(const ObjectSample* sample, const JfrBlobHandle& blob);
  // Hash table callbacks
  void on_link(const BlobEntry* entry) const;
  bool on_equals(uintptr_t hash, const BlobEntry* entry) const;
  void on_unlink(BlobEntry* entry) const;
};

JfrBlobHandle BlobCache::get(const ObjectSample* sample) {
  assert(sample != nullptr, "invariant");
  _lookup_id = sample->stack_trace_id();
  assert(_lookup_id != 0, "invariant");
  BlobEntry* const entry = _table.lookup_only(sample->stack_trace_hash());
  return entry != nullptr ? entry->literal() : JfrBlobHandle();
}

void BlobCache::put(const ObjectSample* sample, const JfrBlobHandle& blob) {
  assert(sample != nullptr, "invariant");
  assert(_table.lookup_only(sample->stack_trace_hash()) == nullptr, "invariant");
  _lookup_id = sample->stack_trace_id();
  assert(_lookup_id != 0, "invariant");
  _table.put(sample->stack_trace_hash(), blob);
}

inline void BlobCache::on_link(const BlobEntry* entry) const {
  assert(entry != nullptr, "invariant");
  assert(entry->id() == 0, "invariant");
  entry->set_id(_lookup_id);
}

inline bool BlobCache::on_equals(uintptr_t hash, const BlobEntry* entry) const {
  assert(entry != nullptr, "invariant");
  assert(entry->hash() == hash, "invariant");
  return entry->id() == _lookup_id;
}

inline void BlobCache::on_unlink(BlobEntry* entry) const {
  assert(entry != nullptr, "invariant");
}

static JfrResourceAreaTraceIdSet* id_set = nullptr;

static void prepare_for_resolution() {
  id_set = new JfrResourceAreaTraceIdSet(initial_size);
}

static bool stack_trace_precondition(const ObjectSample* sample) {
  assert(sample != nullptr, "invariant");
  return sample->has_stack_trace_id() && !sample->is_dead();
}

static void add_to_leakp_set(const ObjectSample* sample) {
  assert(sample != nullptr, "invariant");
  oop object = sample->object();
  if (object == nullptr) {
    return;
  }
  JfrTraceId::load_leakp(object->klass());
}

class StackTraceBlobInstaller {
 private:
  BlobCache _cache;
  void install(ObjectSample* sample);
  const JfrStackTrace* resolve(const ObjectSample* sample) const;
 public:
  StackTraceBlobInstaller() : _cache(JfrOptionSet::old_object_queue_size()) {
    prepare_for_resolution();
  }
  void sample_do(ObjectSample* sample) {
    if (stack_trace_precondition(sample)) {
      add_to_leakp_set(sample);
      install(sample);
    }
  }
};

#ifdef ASSERT
static void validate_stack_trace(const ObjectSample* sample, const JfrStackTrace* stack_trace) {
  assert(!sample->has_stacktrace(), "invariant");
  assert(stack_trace != nullptr, "invariant");
  assert(stack_trace->hash() == sample->stack_trace_hash(), "invariant");
  assert(stack_trace->id() == sample->stack_trace_id(), "invariant");
}
#endif

inline const JfrStackTrace* StackTraceBlobInstaller::resolve(const ObjectSample* sample) const {
  return JfrStackTraceRepository::lookup_for_leak_profiler(sample->stack_trace_hash(), sample->stack_trace_id());
}

void StackTraceBlobInstaller::install(ObjectSample* sample) {
  JfrBlobHandle blob = _cache.get(sample);
  if (blob.valid()) {
    sample->set_stacktrace(blob);
    return;
  }
  const JfrStackTrace* const stack_trace = resolve(sample);
  DEBUG_ONLY(validate_stack_trace(sample, stack_trace));
  JfrCheckpointWriter writer;
  writer.write_type(TYPE_STACKTRACE);
  writer.write_count(1);
  ObjectSampleCheckpoint::write_stacktrace(stack_trace, writer);
  blob = stack_trace->should_write() ? writer.copy() : writer.move();
  _cache.put(sample, blob);
  sample->set_stacktrace(blob);
}

static void install_stack_traces(const ObjectSampler* sampler) {
  assert(sampler != nullptr, "invariant");
  const ObjectSample* const last = sampler->last();
  assert(last != nullptr, "invariant");
  assert(last != sampler->last_resolved(), "invariant");
  ResourceMark rm;
  StackTraceBlobInstaller installer;
  iterate_samples(installer);
}

// Resets the blob write states from the previous epoch.
static void reset_blob_write_state(const ObjectSampler* sampler, JavaThread* jt) {
  assert(sampler != nullptr, "invariant");
  const ObjectSample* sample = sampler->last_resolved();
  while (sample != nullptr) {
    if (sample->has_stacktrace()) {
      sample->stacktrace()->reset_write_state();
    }
    if (sample->has_thread()) {
      sample->thread()->reset_write_state();
    }
    if (sample->has_type_set()) {
      sample->type_set()->reset_write_state();
    }
    sample = sample->next();
  }
}

void ObjectSampleCheckpoint::on_rotation(const ObjectSampler* sampler) {
  assert(sampler != nullptr, "invariant");
  assert(LeakProfiler::is_running(), "invariant");
  JavaThread* const thread = JavaThread::current();
  DEBUG_ONLY(JfrJavaSupport::check_java_thread_in_native(thread);)
  reset_blob_write_state(sampler, thread);
  if (!ObjectSampler::has_unresolved_entry()) {
    return;
  }
  {
    // can safepoint here
    ThreadInVMfromNative transition(thread);
    MutexLocker lock(ClassLoaderDataGraph_lock);
    // the lock is needed to ensure the unload lists do not grow in the middle of inspection.
    install_stack_traces(sampler);
  }
  JfrStackTraceRepository::clear_leak_profiler();
}

static bool is_klass_unloaded(traceid klass_id) {
  assert(ClassLoaderDataGraph_lock->owned_by_self(), "invariant");
  return JfrKlassUnloading::is_unloaded(klass_id);
}

static bool is_processed(traceid method_id) {
  assert(method_id != 0, "invariant");
  assert(id_set != nullptr, "invariant");
  return !id_set->add(method_id);
}

void ObjectSampleCheckpoint::add_to_leakp_set(const InstanceKlass* ik, traceid method_id) {
  assert(ik != nullptr, "invariant");
  if (is_processed(method_id) || is_klass_unloaded(JfrMethodLookup::klass_id(method_id))) {
    return;
  }
  const Method* const method = JfrMethodLookup::lookup(ik, method_id);
  assert(method != nullptr, "invariant");
  assert(method->method_holder() == ik, "invariant");
  JfrTraceId::load_leakp(ik, method);
}

void ObjectSampleCheckpoint::write_stacktrace(const JfrStackTrace* trace, JfrCheckpointWriter& writer) {
  assert(trace != nullptr, "invariant");
  // JfrStackTrace
  writer.write(trace->id());
  writer.write((u1)!trace->_reached_root);
  const int number_of_frames = trace->number_of_frames();
  writer.write<u4>(number_of_frames);
  // JfrStackFrames
  for (int i = 0; i < number_of_frames; ++i) {
    const JfrStackFrame& frame = trace->_frames->at(i);
    frame.write(writer);
    add_to_leakp_set(frame._klass, frame._methodid);
  }
}

static void write_blob(const JfrBlobHandle& blob, JfrCheckpointWriter& writer) {
  blob->exclusive_write(writer);
}

static void write_type_set_blob(const ObjectSample* sample, JfrCheckpointWriter& writer) {
  if (sample->has_type_set()) {
    write_blob(sample->type_set(), writer);
  }
}

static void write_thread_blob(const ObjectSample* sample, JfrCheckpointWriter& writer) {
  assert(sample->has_thread(), "invariant");
  if (sample->is_virtual_thread() || has_thread_exited(sample->thread_id())) {
    write_blob(sample->thread(), writer);
  }
}

static JfrResourceAreaTraceIdSet* _stacktrace_id_set = nullptr;

static inline bool should_write(const JfrStackTrace* stacktrace) {
  assert(stacktrace != nullptr, "invariant");
  assert(_stacktrace_id_set != nullptr, "invariant");
  return stacktrace->should_write() && _stacktrace_id_set->contains(stacktrace->id());
}

class LeakProfilerStackTraceWriter {
 private:
  JfrCheckpointWriter& _writer;
  int _count;
 public:
  LeakProfilerStackTraceWriter(JfrCheckpointWriter& writer) : _writer(writer), _count(0) {
    assert(_stacktrace_id_set != nullptr, "invariant");
  }

  int count() const { return _count; }

  void operator()(const JfrStackTrace* stacktrace) {
    if (should_write(stacktrace)) {
      stacktrace->write(_writer);
      ++_count;
    }
  }
};

void ObjectSampleCheckpoint::write_stacktraces(Thread* thread) {
  assert(_stacktrace_id_set != nullptr, "invariant");
  assert(_stacktrace_id_set->is_nonempty(), "invariant");

  JfrCheckpointWriter writer(thread);
  writer.write_type(TYPE_STACKTRACE);
  const int64_t count_offset = writer.reserve(sizeof(u4)); // Don't know how many yet

  LeakProfilerStackTraceWriter lpstw(writer);
  JfrStackTraceRepository::iterate_leakprofiler(lpstw);
  assert(lpstw.count() == _stacktrace_id_set->size(), "invariant");
  writer.write_count(lpstw.count(), count_offset);
}

static void write_stacktrace_blob(const ObjectSample* sample, JfrCheckpointWriter& writer) {
  assert(sample != nullptr, "invariant");
  assert(_stacktrace_id_set != nullptr, "invariant");
  if (sample->has_stacktrace()) {
    write_blob(sample->stacktrace(), writer);
    return;
  }
  const traceid stacktrace_id = sample->stack_trace_id();
  if (stacktrace_id != 0) {
    _stacktrace_id_set->add(stacktrace_id);
  }
}

static void write_blobs(const ObjectSample* sample, JfrCheckpointWriter& writer) {
  assert(sample != nullptr, "invariant");
  write_stacktrace_blob(sample, writer);
  write_thread_blob(sample, writer);
  write_type_set_blob(sample, writer);
}

class BlobWriter {
 private:
  const ObjectSampler* _sampler;
  JfrCheckpointWriter& _writer;
  const jlong _last_sweep;
 public:
  BlobWriter(const ObjectSampler* sampler, JfrCheckpointWriter& writer, jlong last_sweep) :
    _sampler(sampler), _writer(writer), _last_sweep(last_sweep) {}
  void sample_do(ObjectSample* sample) {
    if (sample->is_alive_and_older_than(_last_sweep)) {
      write_blobs(sample, _writer);
    }
  }
};

static void write_sample_blobs(const ObjectSampler* sampler, bool emit_all, Thread* thread) {
  // sample set is predicated on time of last sweep
  const jlong last_sweep = emit_all ? max_jlong : ObjectSampler::last_sweep();
  JfrCheckpointWriter writer(thread, false);
  BlobWriter cbw(sampler, writer, last_sweep);
  iterate_samples(cbw, true);
}

static inline unsigned int set_size() {
  const unsigned int queue_size = static_cast<unsigned int>(JfrOptionSet::old_object_queue_size());
  return queue_size > initial_size ? queue_size : initial_size;
}

void ObjectSampleCheckpoint::write(const ObjectSampler* sampler, EdgeStore* edge_store, bool emit_all, Thread* thread) {
  assert(sampler != nullptr, "invariant");
  assert(edge_store != nullptr, "invariant");
  assert(thread != nullptr, "invariant");
  {
    ResourceMark rm(thread);
    _stacktrace_id_set = new JfrResourceAreaTraceIdSet(set_size());
    write_sample_blobs(sampler, emit_all, thread);
    if (_stacktrace_id_set->is_nonempty()) {
      write_stacktraces(thread);
    }
  }
  // write reference chains
  if (!edge_store->is_empty()) {
    JfrCheckpointWriter writer(thread);
    ObjectSampleWriter osw(writer, edge_store);
    edge_store->iterate(osw);
  }
}

// This routine has exclusive access to the sampler instance on entry.
void ObjectSampleCheckpoint::on_type_set(JavaThread* jt) {
  assert(LeakProfiler::is_running(), "invariant");
  DEBUG_ONLY(JfrJavaSupport::check_java_thread_in_vm(JavaThread::current());)
  assert(ClassLoaderDataGraph_lock->owned_by_self(), "invariant");
  if (!ObjectSampler::has_unresolved_entry()) {
    return;
  }
  ObjectSample* const last = ObjectSampler::sampler()->last();
  assert(last != nullptr, "invariant");
  assert(last != ObjectSampler::sampler()->last_resolved(), "invariant");
  JfrReferenceCountedStorage::install(last, ObjectSampler::sampler()->last_resolved());
  ObjectSampler::sampler()->set_last_resolved(last);
}
