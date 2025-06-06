/*
 * Copyright (c) 1997, 2025, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_RUNTIME_SUSPENDEDTHREADTASK_HPP
#define SHARE_RUNTIME_SUSPENDEDTHREADTASK_HPP

class Thread;

class SuspendedThreadTaskContext {
 private:
  Thread* _thread;
  void* _ucontext;
 public:
  SuspendedThreadTaskContext(Thread* thread, void* ucontext) : _thread(thread), _ucontext(ucontext) {}
  Thread* thread() const { return _thread; }
  void* ucontext() const { return _ucontext; }
};

class SuspendedThreadTask {
 private:
  Thread* _thread;
  void internal_do_task();
 protected:
  ~SuspendedThreadTask() {}
 public:
  SuspendedThreadTask(Thread* thread) : _thread(thread) {}
  void run() { internal_do_task(); }
  virtual void do_task(const SuspendedThreadTaskContext& context) = 0;
};

#endif // SHARE_RUNTIME_SUSPENDEDTHREADTASK_HPP
