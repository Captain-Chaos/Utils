/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.util;


import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.util.ProgressReceiver.OperationCancelledByUser;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.pepsoft.util.ExceptionUtils.chainContains;

/**
 * A manager of parallel progress receivers, which reports to one parent
 * progress receiver, combining the progress values and managing the reporting
 * of exceptions or task completion.
 * 
 * <ol><li>Instantiate it with a parent progress receiver, and a task count (if
 * known). <strong>Note:</strong> the parent progress receiver should be thread
 * safe and not make assumptions about which threads its methods will be invoked
 * from!
 * 
 * <li>Invoke createProgressReceiver() as many times as needed.
 * <strong>Note:</strong> if the manager has not been created with a task count
 * you cannot invoke this method any more after the first task has started!
 * 
 * <li>Start the tasks in background threads and invoke {@link #join()} on the
 * manager to wait for all tasks to complete (defined as either invoking
 * {@link ProgressReceiver#done()} or {@link ProgressReceiver#exceptionThrown(Throwable)}
 * on their progress receivers).</ol>
 *
 * <p>If a task invokes {@link ProgressReceiver#exceptionThrown(Throwable)} it will
 * be reported to the parent progress receiver, and all subsequent invocations
 * on their progress receivers by any other tasks will result in an
 * {@link OperationCancelled} exception being thrown. If any
 * more exceptions are reported these are <em>not</em> reported to the parent
 * progress receiver (instead they are logged using the java.util logging
 * framework). Also, if an exception has been reported,
 * {@link ProgressReceiver#done()} will not subsequently be invoked on the
 * parent progress receiver.
 * 
 * <p>If no exceptions are reported, {@link ProgressReceiver#done()} will be
 * invoked on the parent progress receiver after the last task has invoked it on
 * its sub progress receiver.
 * 
 * <p>All invocations on {@link ProgressReceiver#setMessage(String)} are passed
 * through unaltered to the parent progress receiver.
 * 
 * <p>If the parent progress receiver throws an {@code OperationCancelled}
 * exception at any time, it is stored and rethrown to every task whenever they
 * next invoke a method (that declares it) on their sub progress receivers. It
 * is immediately rethrown to the calling task.
 *
 * @author pepijn
 */
public class ParallelProgressManager {
    private static final VarHandle INT_ARRAY_HANDLE = MethodHandles.arrayElementVarHandle(int[].class);
    private final ReentrantLock progressLock = new ReentrantLock();
    private final ProgressReceiver progressReceiver;
    private final AtomicInteger taskCount = new AtomicInteger();//Top bit of task count indicates if done has been called
    private final AtomicInteger taskIncrementer = new AtomicInteger();
    private final AtomicLong taskProgress = new AtomicLong();
    private final AtomicReference<Throwable> exception = new AtomicReference<>();
    private volatile boolean started;
    private AtomicLongArray taskProgresses;
    private volatile int[] taskDones;
    private final int taskLimit;


    public ParallelProgressManager(ProgressReceiver progressReceiver) {
        this.progressReceiver = progressReceiver;
        this.taskLimit = -1;
    }
    
    public ParallelProgressManager(ProgressReceiver progressReceiver, int taskCount) {
        this.progressReceiver = progressReceiver;
        this.taskLimit = taskCount;
        this.startIfNot();
    }
    
    public ProgressReceiver createProgressReceiver() {
        this.progressLock.lock();//Use the lock here to ensure synchronization with starting
        int id = 0;
        try {
            if (this.taskLimit == -1 && this.started) {
                throw new IllegalStateException("Cannot create new progress receivers after tasks have started");
            }
            id = this.taskIncrementer.getAndIncrement();
            if (id >= this.taskLimit) {
                throw new IllegalStateException("Attempt to create more sub progress receivers than indicated task count (" + this.taskLimit + ")");
            }
        } finally {
            this.progressLock.unlock();
        }
        this.taskCount.incrementAndGet();
        return new SubProgressReceiver(id);
    }
    
    /** @noinspection BusyWait*/
    public void join() throws InterruptedException {
        while ((!this.started) || (this.getTaskCount()) != 0) {
            Thread.sleep(100);
        }
    }
    
    public boolean isExceptionThrown() {
        return this.exception.get() == null;
    }

    private int getTaskCount() {
        return this.taskCount.get()&(~(1<<31));
    }

    private void setProgress(int index, float subProgress) throws OperationCancelled {
        final long SCALE_FACTOR = 1000;
        final long thisVal = (long) (subProgress * SCALE_FACTOR);
        this.startIfNot();
        this.cancelIfPreviousException();
        long totalProgress = this.taskProgress.addAndGet(thisVal-this.taskProgresses.getAndSet(index, thisVal));
        float progress = (float) ((((double)totalProgress)/this.getTaskCount())/SCALE_FACTOR);

        try {
            if (this.progressLock.tryLock()) {
                try {
                    this.progressReceiver.setProgress(progress);
                } finally {
                    this.progressLock.unlock();
                }
            }
        } catch (OperationCancelled e) {
            if (this.exception.getAndSet(e) == null) {
                this.progressLock.lock();
                try {
                    this.progressReceiver.exceptionThrown(e);
                } finally {
                    this.progressLock.unlock();
                }
            }
            throw e;
        }
    }
    
    private void exceptionThrown(int index, Throwable exception) {
        this.startIfNot();
        this.stopRunningIfNot(index);
        var ex =this.exception.compareAndExchange(null, exception);
        if (ex == null) {
            this.progressLock.lock();
            try {
                this.progressReceiver.exceptionThrown(exception);
            } finally {
                this.progressLock.unlock();
            }
        } else if (chainContains(exception, OperationCancelledByUser.class)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Operation cancelled by user; not reporting to progress receiver");
            }
        } else if (chainContains(exception, OperationCancelled.class)) {
            logger.debug("Operation cancelled on thread {} (message: \"{}\")", Thread.currentThread().getName(), exception.getMessage());
        } else {
            logger.error("Secondary exception from parallel task; not reporting to progress receiver", exception);
        }
    }

    private void done(int index) {
        this.startIfNot();
        this.stopRunningIfNot(index);
        if (this.exception.get()==null) {
            if (this.taskCount.compareAndExchange(0, 1<<31)==0) {
                this.progressLock.lock();
                try {
                    this.progressReceiver.done();
                } finally {
                    this.progressLock.unlock();
                }
            }
        }
    }

    private void setMessage(int index, String message) throws OperationCancelled {
        this.startIfNot();
        this.cancelIfPreviousException();
        this.progressLock.lock();
        try {
            this.progressReceiver.setMessage(message);
        } finally {
            this.progressLock.unlock();
        }
    }

    private void checkForCancellation() throws OperationCancelled {
        this.startIfNot();
        this.cancelIfPreviousException();
    }

    private void subProgressStarted(org.pepsoft.util.SubProgressReceiver subProgressReceiver) throws OperationCancelled {
        this.startIfNot();
        this.cancelIfPreviousException();
        this.progressLock.lock();
        try {
            this.progressReceiver.subProgressStarted(subProgressReceiver);
        } finally {
            this.progressLock.unlock();
        }
    }

    private void startIfNot() {
        if (this.started) return;
        this.progressLock.lock();
        if (this.started) return;
        int taskCount = this.taskLimit;
        if (taskCount == -1) taskCount = this.taskIncrementer.get();
        this.taskProgresses = new AtomicLongArray(taskCount);
        this.taskDones = new int[(taskCount+31)/32];
        this.started = true;
        this.progressLock.unlock();
    }

    private void stopRunningIfNot(int index) {
        int msk = 1<<(index&31);
        if ((this.taskDones[index>>5]&msk)!=0) return;//Already stopped
        if (((int)INT_ARRAY_HANDLE.getAndBitwiseOr(this.taskDones, index>>5, msk)&msk)!=0) return;//Already stopped
        this.taskCount.decrementAndGet();
    }

    private void cancelIfPreviousException() throws OperationCancelled {
        var ex = this.exception.get();
        if (ex != null) {
            throw new OperationCancelled("Operation cancelled due to exception on other thread (type: " + ex.getClass().getSimpleName() + ", message: " + ex.getMessage() + ")", ex);
        }
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ParallelProgressManager.class);

    private class SubProgressReceiver implements ProgressReceiver {
        private SubProgressReceiver(int index) {
            this.index = index;
        }
        
        @Override
        public void setProgress(float progress) throws OperationCancelled {
            ParallelProgressManager.this.setProgress(this.index, progress);
        }

        @Override
        public void exceptionThrown(Throwable exception) {
            ParallelProgressManager.this.exceptionThrown(this.index, exception);
        }

        @Override
        public void done() {
            ParallelProgressManager.this.done(this.index);
        }

        @Override
        public void setMessage(String message) throws OperationCancelled {
            ParallelProgressManager.this.setMessage(this.index, message);
        }

        @Override
        public void checkForCancellation() throws OperationCancelled {
            ParallelProgressManager.this.checkForCancellation();
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("Not supported");
        }

        @Override
        public void subProgressStarted(org.pepsoft.util.SubProgressReceiver subProgressReceiver) throws OperationCancelled {
            ParallelProgressManager.this.subProgressStarted(subProgressReceiver);
        }

        private final int index;
    }
}