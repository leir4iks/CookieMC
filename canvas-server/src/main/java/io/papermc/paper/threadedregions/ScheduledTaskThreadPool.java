package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.concurrentutil.util.TimeUtil;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class ScheduledTaskThreadPool {

    public static final long DEADLINE_NOT_SET = Long.MIN_VALUE;

    private final ThreadFactory threadFactory;
    private final long stealThresholdNS;
    private final long taskTimeSliceNS;

    private final COWArrayList<TickThreadRunner> coreThreads = new COWArrayList<>(TickThreadRunner.class);
    private final COWArrayList<TickThreadRunner> aliveThreads = new COWArrayList<>(TickThreadRunner.class);

    private long runnerIdGenerator;
    private boolean shutdown;

    private final ConcurrentSkipListMap<WaitState, WaitState> waitingOrIdleRunners = new ConcurrentSkipListMap<>(WaitState.OLDEST_FIRST);

    private final ConcurrentSkipListMap<ScheduledTickTask, ScheduledTickTask> unwatchedScheduledTicks = new ConcurrentSkipListMap<>(ScheduledTickTask.TICK_COMPARATOR);
    private final ConcurrentSkipListMap<ScheduledTickTask, ScheduledTickTask> scheduledTasks = new ConcurrentSkipListMap<>(ScheduledTickTask.TASK_COMPARATOR);

    public ScheduledTaskThreadPool(final ThreadFactory threadFactory, final long stealThresholdNS,
                                   final long taskTimeSliceNS) {
        this.threadFactory = threadFactory;
        this.stealThresholdNS = stealThresholdNS;
        this.taskTimeSliceNS = taskTimeSliceNS;

        if (threadFactory == null) {
            throw new NullPointerException("Null thread factory");
        }
        if (stealThresholdNS < 0L) {
            throw new IllegalArgumentException("Steal threshold must be >= 0");
        }
        if (taskTimeSliceNS <= 0L) {
            throw new IllegalArgumentException("Task time slice must be > 0");
        }
    }

    private static <K,V> K firstEntry(final ConcurrentSkipListMap<K, V> map) {
        final Map.Entry<K,V> first = map.firstEntry();
        return first == null ? null : first.getKey();
    }

    private static ScheduledTickTask findFirstNonTaken(final ConcurrentSkipListMap<ScheduledTickTask, ScheduledTickTask> map) {
        ScheduledTickTask first;
        while ((first = firstEntry(map)) != null && (first.isTaken() || first.getTick() == null)) {
            map.remove(first);
        }

        return first;
    }

    private static ScheduledTickTask findFirstNonTakenNonWatched(final ConcurrentSkipListMap<ScheduledTickTask, ScheduledTickTask> map) {
        ScheduledTickTask first;
        while ((first = firstEntry(map)) != null && (first.isTaken() || first.isWatched() || first.getTick() == null)) {
            map.remove(first);
            if (!first.isTaken() && !first.isWatched() && first.getTick() != null) {
                map.put(first, first);
            }
        }

        return first;
    }

    private static Thread[] getThreads(final COWArrayList<TickThreadRunner> list) {
        final TickThreadRunner[] runners = list.getArray();
        final Thread[] ret = new Thread[runners.length];

        for (int i = 0; i < ret.length; ++i) {
            ret[i] = runners[i].thread;
        }

        return ret;
    }

    public Thread[] getCoreThreads() {
        return getThreads(this.coreThreads);
    }

    public Thread[] getAliveThreads() {
        return getThreads(this.aliveThreads);
    }

    public ScheduledTaskThreadPool setCoreThreads(final int threads) {
        synchronized (this) {
            if (this.shutdown) {
                return this;
            }

            final TickThreadRunner[] currRunners = this.coreThreads.getArray();
            if (currRunners.length == threads) {
                return this;
            }

            if (threads < currRunners.length) {
                for (int i = 0, difference = currRunners.length - threads; i < difference; ++i) {
                    final TickThreadRunner remove = currRunners[currRunners.length - i - 1];

                    remove.halt();
                    this.coreThreads.remove(remove);
                }

                this.interruptAllRunners();

                return this;
            } else {
                for (int i = 0, difference = threads - currRunners.length; i < difference; ++i) {
                    final TickThreadRunner runner = new TickThreadRunner(this, this.runnerIdGenerator++);
                    final Thread thread = runner.thread = this.threadFactory.newThread(runner);

                    this.coreThreads.add(runner);
                    this.aliveThreads.add(runner);

                    thread.start();
                }

                return this;
            }
        }
    }

    public void halt() {
        synchronized (this) {
            this.shutdown = true;
        }

        for (final TickThreadRunner runner : this.coreThreads.getArray()) {
            runner.halt();
        }
    }

    public boolean join(final long msToWait) {
        try {
            return this.join(msToWait, false);
        } catch (final InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public boolean joinInterruptable(final long msToWait) throws InterruptedException {
        return this.join(msToWait, true);
    }

    private boolean join(final long msToWait, final boolean interruptable) throws InterruptedException {
        final long nsToWait = msToWait * (1000 * 1000);
        final long start = System.nanoTime();
        final long deadline = start + nsToWait;
        boolean interrupted = false;
        try {
            for (final TickThreadRunner runner : this.aliveThreads.getArray()) {
                final Thread thread = runner.thread;
                for (;;) {
                    if (!thread.isAlive()) {
                        break;
                    }
                    final long current = System.nanoTime();
                    if (current >= deadline && msToWait > 0L) {
                        return false;
                    }

                    try {
                        thread.join(msToWait <= 0L ? 0L : Math.max(1L, (deadline - current) / (1000 * 1000)));
                    } catch (final InterruptedException ex) {
                        if (interruptable) {
                            throw ex;
                        }
                        interrupted = true;
                    }
                }
            }

            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void interruptAllRunners() {
        for (final TickThreadRunner runner : this.coreThreads.getArray()) {
            runner.interrupt();
        }
    }

    private void interruptOneRunner() {
        for (final TickThreadRunner runner : this.coreThreads.getArray()) {
            if (runner.interrupt()) {
                return;
            }
        }
    }

    private void insert(final SchedulableTick tick, final boolean hasTasks) {
        final long scheduleTime = tick.getScheduledStart();
        final long timeNow = System.nanoTime();

        for (;;) {
            final Map.Entry<WaitState, WaitState> lastIdle = this.waitingOrIdleRunners.firstEntry();
            final WaitState waitState;
            if (lastIdle == null
                || ((waitState = lastIdle.getKey()).deadline != DEADLINE_NOT_SET && waitState.deadline - scheduleTime < 0L)) {
                final ScheduledTickTask task = new ScheduledTickTask(
                    tick,
                    scheduleTime - this.stealThresholdNS,
                    hasTasks ? timeNow : DEADLINE_NOT_SET,
                    null
                );
                tick.task = task;

                this.unwatchedScheduledTicks.put(task, task);
                if (hasTasks) {
                    this.scheduledTasks.put(task, task);
                }

                if (!this.waitingOrIdleRunners.isEmpty()) {
                    this.interruptOneRunner();
                }
                break;
            } else {
                if (this.waitingOrIdleRunners.remove(waitState) == null) {
                    continue;
                }

                final ScheduledTickTask task = new ScheduledTickTask(
                    tick, scheduleTime, hasTasks ? timeNow : DEADLINE_NOT_SET, waitState.runner
                );
                tick.task = task;

                this.unwatchedScheduledTicks.put(task, task);
                waitState.runner.scheduledTicks.put(task, task);
                if (hasTasks) {
                    this.scheduledTasks.put(task, task);
                    waitState.runner.scheduledTasks.put(task, task);
                }

                if (!waitState.runner.interrupt() && waitState.runner.isHalted()) {
                    this.interruptOneRunner();
                }
                break;
            }
        }

        if (!hasTasks && tick.hasTasks()) {
            this.notifyTasks(tick);
        }
    }

    public void schedule(final SchedulableTick tick) {
        final boolean hasTasks = tick.hasTasks();
        if ((!hasTasks && !tick.setScheduled()) || (hasTasks && !tick.setScheduledTasks())) {
            throw new IllegalStateException("Task is already scheduled");
        }

        this.insert(tick, hasTasks);
    }

    public void notifyTasks(final SchedulableTick tick) {
        if (!tick.isScheduled() || !tick.upgradeToScheduledTasks()) {
            return;
        }

        final ScheduledTickTask task = tick.task;
        if (task == null || task.isTaken()) {
            return;
        }

        task.setLastTaskNotify(System.nanoTime());

        final TickThreadRunner runner = task.owner;
        this.scheduledTasks.put(task, task);
        if (runner != null) {
            runner.scheduledTasks.put(task, task);
            runner.interruptIfWaiting();
        }
    }

    public boolean cancel(final SchedulableTick tick) {
        final boolean ret = tick.cancel();

        if (!ret) {
            return false;
        }

        final ScheduledTickTask task = tick.task;
        if (task != null && task.take()) {
            this.unwatchedScheduledTicks.remove(task);
            this.scheduledTasks.remove(task);
            final TickThreadRunner owner = task.owner;
            if (owner != null) {
                owner.scheduledTicks.remove(task);
                owner.scheduledTasks.remove(task);
            }
        }

        return ret;
    }

    public static abstract class SchedulableTick {
        private static final AtomicLong ID_GENERATOR = new AtomicLong();
        public final long id = ID_GENERATOR.getAndIncrement();

        private long scheduledStart = DEADLINE_NOT_SET;

        private static final int STATE_UNSCHEDULED       = 1 << 0;
        private static final int STATE_SCHEDULED         = 1 << 1;
        private static final int STATE_SCHEDULED_TASKS   = 1 << 2;
        private static final int STATE_TICKING           = 1 << 3;
        private static final int STATE_TASKS             = 1 << 4;
        private static final int STATE_TICKING_CANCELLED = 1 << 5;
        private static final int STATE_TASKS_CANCELLED   = 1 << 6;
        private static final int STATE_CANCELLED         = 1 << 7;
        private volatile int state = STATE_UNSCHEDULED;
        private static final VarHandle STATE_HANDLE = ConcurrentUtil.getVarHandle(SchedulableTick.class, "state", int.class);

        private volatile ScheduledTickTask task;

        public int getStateVolatile() {
            return (int)STATE_HANDLE.getVolatile(this);
        }

        private void setStateVolatile(final int value) {
            STATE_HANDLE.setVolatile(this, value);
        }

        private int compareAndExchangeStateVolatile(final int expect, final int update) {
            return (int)STATE_HANDLE.compareAndExchange(this, expect, update);
        }

        private boolean isScheduled() {
            final int currentState = this.getStateVolatile();
            return currentState == STATE_SCHEDULED || currentState == STATE_SCHEDULED_TASKS;
        }

        private boolean upgradeToScheduledTasks() {
            return STATE_SCHEDULED == this.compareAndExchangeStateVolatile(STATE_SCHEDULED, STATE_SCHEDULED_TASKS);
        }

        private boolean setScheduled() {
            return STATE_UNSCHEDULED == this.compareAndExchangeStateVolatile(STATE_UNSCHEDULED, STATE_SCHEDULED);
        }

        private boolean setScheduledTasks() {
            return STATE_UNSCHEDULED == this.compareAndExchangeStateVolatile(STATE_UNSCHEDULED, STATE_SCHEDULED_TASKS);
        }

        public boolean cancel() {
            for (int currState = this.getStateVolatile();;) {
                switch (currState) {
                    case STATE_UNSCHEDULED:
                        return false;
                    case STATE_SCHEDULED:
                    case STATE_SCHEDULED_TASKS:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(currState, STATE_CANCELLED))) {
                            return true;
                        }
                        continue;
                    case STATE_TICKING:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(currState, STATE_TICKING_CANCELLED))) {
                            return true;
                        }
                        continue;
                    case STATE_TASKS:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(currState, STATE_TASKS_CANCELLED))) {
                            return true;
                        }
                        continue;
                    case STATE_TICKING_CANCELLED:
                    case STATE_TASKS_CANCELLED:
                    case STATE_CANCELLED:
                        return false;
                    default:
                        throw new IllegalStateException("Unknown state: " + currState);
                }
            }
        }

        private boolean markTicking() {
            for (int currState = this.getStateVolatile();;) {
                switch (currState) {
                    case STATE_UNSCHEDULED:
                        throw new IllegalStateException();
                    case STATE_SCHEDULED:
                    case STATE_SCHEDULED_TASKS:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(currState, STATE_TICKING))) {
                            return true;
                        }
                        continue;
                    case STATE_TICKING:
                    case STATE_TASKS:
                    case STATE_TICKING_CANCELLED:
                    case STATE_TASKS_CANCELLED:
                    case STATE_CANCELLED:
                        return false;
                    default:
                        throw new IllegalStateException("Unknown state: " + currState);
                }
            }
        }

        private boolean markTasks() {
            for (int currState = this.getStateVolatile();;) {
                switch (currState) {
                    case STATE_UNSCHEDULED:
                        throw new IllegalStateException();
                    case STATE_SCHEDULED:
                    case STATE_SCHEDULED_TASKS:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(currState, STATE_TASKS))) {
                            return true;
                        }
                        continue;
                    case STATE_TICKING:
                    case STATE_TASKS:
                    case STATE_TICKING_CANCELLED:
                    case STATE_TASKS_CANCELLED:
                    case STATE_CANCELLED:
                        return false;
                    default:
                        throw new IllegalStateException("Unknown state: " + currState);
                }
            }
        }

        protected final long getScheduledStart() {
            return this.scheduledStart;
        }

        protected final void setScheduledStart(final long value) {
            this.scheduledStart = value;
        }

        public abstract boolean runTick();

        private boolean tick() {
            if (!this.markTicking()) {
                return false;
            }

            final boolean tickRes = this.runTick();

            if (!tickRes) {
                this.setStateVolatile(STATE_CANCELLED);
                return false;
            }

            for (int currState = this.getStateVolatile();;) {
                switch (currState) {
                    case STATE_TICKING:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(STATE_TICKING, STATE_SCHEDULED))) {
                            return true;
                        }
                        continue;
                    case STATE_TICKING_CANCELLED:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(STATE_TICKING_CANCELLED, STATE_CANCELLED))) {
                            return false;
                        }
                        continue;
                    default:
                        throw new IllegalStateException("Unknown state: " + currState);
                }
            }
        }

        public abstract boolean hasTasks();

        public abstract boolean runTasks(final BooleanSupplier canContinue);

        private boolean tasks(final BooleanSupplier canContinue) {
            if (!this.markTasks()) {
                return false;
            }

            final boolean taskRes = this.runTasks(canContinue);

            if (!taskRes) {
                this.setStateVolatile(STATE_CANCELLED);
                return false;
            }

            for (int currState = this.getStateVolatile();;) {
                switch (currState) {
                    case STATE_TASKS:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(STATE_TASKS, STATE_SCHEDULED))) {
                            return true;
                        }
                        continue;
                    case STATE_TASKS_CANCELLED:
                        if (currState == (currState = this.compareAndExchangeStateVolatile(STATE_TASKS_CANCELLED, STATE_CANCELLED))) {
                            return false;
                        }
                        continue;
                    default:
                        throw new IllegalStateException("Unknown state: " + currState);
                }
            }
        }

        @Override
        public String toString() {
            return "SchedulableTick:{" +
                "class=" + this.getClass().getName() + "," +
                "state=" + this.state +
                "}";
        }
    }

    private static final class WaitState {

        private static final Comparator<WaitState> OLDEST_FIRST = (final WaitState w1, final WaitState w2) -> {
            final long d1 = w1.deadline;
            final long d2 = w2.deadline;

            if (d1 == DEADLINE_NOT_SET && d2 != DEADLINE_NOT_SET) {
                return -1;
            }
            if (d1 != DEADLINE_NOT_SET && d2 == DEADLINE_NOT_SET) {
                return 1;
            }

            final int timeCmp = TimeUtil.compareTimes(d2, d1);
            if (timeCmp != 0) {
                return timeCmp;
            }

            return Long.signum(w2.id - w1.id);
        };

        private final long id;
        private final long deadline;
        private final TickThreadRunner runner;

        private WaitState(final long id, final long deadline, final TickThreadRunner runner) {
            this.id = id;
            this.deadline = deadline;
            this.runner = runner;
        }
    }

    private static final class TickThreadRunner implements Runnable {

        private final ScheduledTaskThreadPool scheduler;
        private final long id;
        private Thread thread;

        private static final int STATE_IDLE      = 1 << 0;
        private static final int STATE_WAITING   = 1 << 1;
        private static final int STATE_TASKS     = 1 << 2;
        private static final int STATE_INTERRUPT = 1 << 3;
        private static final int STATE_TICKING   = 1 << 4;
        private static final int STATE_HALTED    = 1 << 5;
        private volatile int state = STATE_INTERRUPT;
        private static final VarHandle STATE_HANDLE = ConcurrentUtil.getVarHandle(TickThreadRunner.class, "state", int.class);

        private WaitState waitState;
        private ScheduledTickTask watch;

        private final ConcurrentSkipListMap<ScheduledTickTask, ScheduledTickTask> scheduledTicks = new ConcurrentSkipListMap<>(ScheduledTickTask.TICK_COMPARATOR);
        private final ConcurrentSkipListMap<ScheduledTickTask, ScheduledTickTask> scheduledTasks = new ConcurrentSkipListMap<>(ScheduledTickTask.TASK_COMPARATOR);

        public TickThreadRunner(final ScheduledTaskThreadPool scheduler, final long id) {
            this.scheduler = scheduler;
            this.id = id;
        }

        private int getStateVolatile() {
            return (int)STATE_HANDLE.getVolatile(this);
        }

        private void setStateVolatile(final int value) {
            STATE_HANDLE.setVolatile(this, value);
        }

        private int compareAndExchangeStateVolatile(final int expect, final int update) {
            return (int)STATE_HANDLE.compareAndExchange(this, expect, update);
        }

        private boolean interruptIfWaiting() {
            for (int curr = this.getStateVolatile();;) {
                switch (curr) {
                    case STATE_INTERRUPT:
                    case STATE_TASKS:
                    case STATE_TICKING:
                    case STATE_HALTED:
                        return false;
                    case STATE_IDLE:
                    case STATE_WAITING:
                        if (curr == (curr = this.compareAndExchangeStateVolatile(curr, STATE_INTERRUPT))) {
                            LockSupport.unpark(this.thread);
                            return true;
                        }
                        continue;
                    default:
                        throw new IllegalStateException("Unknown state: " + curr);
                }
            }
        }

        private boolean interrupt() {
            for (int curr = this.getStateVolatile();;) {
                switch (curr) {
                    case STATE_INTERRUPT:
                    case STATE_TICKING:
                    case STATE_HALTED:
                        return false;
                    case STATE_IDLE:
                    case STATE_WAITING:
                    case STATE_TASKS:
                        if (curr == (curr = this.compareAndExchangeStateVolatile(curr, STATE_INTERRUPT))) {
                            if (curr == STATE_IDLE || curr == STATE_WAITING) {
                                LockSupport.unpark(this.thread);
                            }
                            return true;
                        }
                        continue;
                    default:
                        throw new IllegalStateException("Unknown state: " + curr);
                }
            }
        }

        private void halt() {
            for (int curr = this.getStateVolatile();;) {
                switch (curr) {
                    case STATE_HALTED:
                        return;
                    case STATE_IDLE:
                    case STATE_WAITING:
                    case STATE_TASKS:
                    case STATE_INTERRUPT:
                    case STATE_TICKING:
                        if (curr == (curr = this.compareAndExchangeStateVolatile(curr, STATE_HALTED))) {
                            if (curr == STATE_IDLE || curr == STATE_WAITING) {
                                LockSupport.unpark(this.thread);
                            }
                            return;
                        }
                        continue;
                    default:
                        throw new IllegalStateException("Unknown state: " + curr);
                }
            }
        }

        private boolean isHalted() {
            return STATE_HALTED == this.getStateVolatile();
        }

        private void setupWaitState(final long deadline) {
            if (this.waitState != null) {
                throw new IllegalStateException("Waitstate already set");
            }
            this.waitState = new WaitState(this.id, deadline, this);
            this.scheduler.waitingOrIdleRunners.put(this.waitState, this.waitState);
        }

        private void cleanWaitState() {
            this.scheduler.waitingOrIdleRunners.remove(this.waitState);
            this.waitState = null;
        }

        private ScheduledTickTask findTick() {
            while (this.getStateVolatile() == STATE_WAITING) {
                final ScheduledTickTask globalFirst = findFirstNonTakenNonWatched(this.scheduler.unwatchedScheduledTicks);
                final ScheduledTickTask ourFirst = findFirstNonTaken(this.scheduledTicks);

                final ScheduledTickTask toWaitFor;
                if (globalFirst == null) {
                    toWaitFor = ourFirst;
                } else if (ourFirst == null) {
                    toWaitFor = globalFirst;
                } else {
                    final long globalStart = globalFirst.tickStart + this.scheduler.stealThresholdNS;
                    final long ourStart = ourFirst.tickStart;
                    toWaitFor = ourStart - globalStart <= 0L ? ourFirst : globalFirst;
                }

                if (toWaitFor == null) {
                    this.setupWaitState(DEADLINE_NOT_SET);
                    this.compareAndExchangeStateVolatile(STATE_WAITING, STATE_IDLE);
                    if (!this.scheduledTicks.isEmpty() || !this.scheduler.unwatchedScheduledTicks.isEmpty()) {
                        this.interrupt();
                    }
                    return null;
                }

                if (toWaitFor == globalFirst) {
                    if (toWaitFor.watch()) {
                        this.scheduler.unwatchedScheduledTicks.remove(toWaitFor);
                        this.watch = toWaitFor;
                    } else if (toWaitFor != ourFirst) {
                        continue;
                    }
                }

                return toWaitFor;
            }
            return null;
        }

        private void cleanupWatch(final boolean wakeThread) {
            if (this.watch != null) {
                this.watch.unwatch();
                if (!this.watch.isTaken()) {
                    this.scheduler.unwatchedScheduledTicks.put(this.watch, this.watch);
                    if (wakeThread) {
                        this.scheduler.interruptOneRunner();
                    }
                }
                this.watch = null;
            }
        }

        private boolean findEarlierTask(final ScheduledTickTask task) {
            final ScheduledTickTask globalFirst = findFirstNonTakenNonWatched(this.scheduler.unwatchedScheduledTicks);
            final ScheduledTickTask ourFirst = findFirstNonTaken(this.scheduledTicks);

            if (globalFirst != null && (globalFirst.tickStart + this.scheduler.stealThresholdNS) - task.tickStart < 0L) {
                return true;
            }
            if (ourFirst != null && ourFirst.tickStart - task.tickStart < 0L) {
                return true;
            }
            return false;
        }

        private void reinsert(final SchedulableTick tick, final TickThreadRunner owner) {
            final ScheduledTickTask newTask = new ScheduledTickTask(
                tick, tick.getScheduledStart(), DEADLINE_NOT_SET, owner
            );
            tick.task = newTask;

            this.scheduler.unwatchedScheduledTicks.put(newTask, newTask);
            if (owner != null) {
                owner.scheduledTicks.put(newTask, newTask);
            }

            if (tick.hasTasks()) {
                this.scheduler.notifyTasks(tick);
            }
        }

        private void runTasks(final ScheduledTickTask task, final long deadline) {
            if (STATE_WAITING != this.compareAndExchangeStateVolatile(STATE_WAITING, STATE_TASKS)) {
                return;
            }

            final SchedulableTick tick = task.getTick();
            if (tick == null || !task.take()) {
                this.compareAndExchangeStateVolatile(STATE_TASKS, STATE_WAITING);
                return;
            }

            this.scheduler.unwatchedScheduledTicks.remove(task);
            this.scheduler.scheduledTasks.remove(task);
            if (task.owner != null) {
                task.owner.scheduledTicks.remove(task);
                task.owner.scheduledTasks.remove(task);
            }

            final BooleanSupplier canContinue = () -> this.getStateVolatile() == STATE_TASKS && (System.nanoTime() - deadline < 0L);

            if (tick.tasks(canContinue)) {
                this.reinsert(tick, task.owner == null ? this : task.owner);
            }

            this.compareAndExchangeStateVolatile(STATE_TASKS, STATE_WAITING);
        }

        private ScheduledTickTask waitForTick() {
            final ScheduledTickTask tick = this.findTick();
            if (tick == null) {
                return null;
            }

            final long tickDeadline = tick.owner == this ? tick.tickStart : tick.tickStart + this.scheduler.stealThresholdNS;
            this.setupWaitState(tickDeadline);

            for (;;) {
                if (this.getStateVolatile() != STATE_WAITING || tick.isTaken() || this.findEarlierTask(tick)) {
                    this.cleanupWatch(false);
                    this.cleanWaitState();
                    this.interrupt();
                    return null;
                }

                final long timeNow = System.nanoTime();
                final ScheduledTickTask ourTask = findFirstNonTaken(this.scheduledTasks);
                final ScheduledTickTask globalTask = findFirstNonTaken(this.scheduler.scheduledTasks);

                if (timeNow - tickDeadline >= 0L) {
                    if (!tick.take()) {
                        continue;
                    }
                    this.cleanWaitState();
                    return tick;
                }

                if (ourTask == null && globalTask == null) {
                    Thread.interrupted();
                    LockSupport.parkNanos("waiting", tickDeadline - timeNow);
                    continue;
                }

                final ScheduledTickTask toTask = (ourTask != null) ? ourTask : globalTask;

                long deadline = Math.min(tickDeadline, timeNow + this.scheduler.taskTimeSliceNS);
                if (this.getStateVolatile() != STATE_WAITING) {
                    continue;
                }
                this.runTasks(toTask, deadline);
            }
        }

        private boolean moveToTickingState() {
            for (int curr = this.getStateVolatile();;) {
                switch (curr) {
                    case STATE_HALTED:
                        return false;
                    case STATE_WAITING:
                    case STATE_INTERRUPT:
                        if (curr == (curr = this.compareAndExchangeStateVolatile(curr, STATE_TICKING))) {
                            if (curr == STATE_INTERRUPT) {
                                this.scheduler.interruptOneRunner();
                            }
                            return true;
                        }
                        continue;
                    default:
                        throw new IllegalStateException("Unknown state: " + curr);
                }
            }
        }

        private void doTick(final ScheduledTickTask task) {
            SchedulableTick tick = task.getTick();
            if (tick == null) {
                this.scheduler.unwatchedScheduledTicks.remove(task);
                this.scheduler.scheduledTasks.remove(task);
                return;
            }
            if (tick.tick()) {
                this.reinsert(tick, this);
            }
        }

        private void doRun() {
            for (;;) {
                if (this.waitState != null) {
                    if (this.waitState.deadline != DEADLINE_NOT_SET) {
                        throw new IllegalStateException();
                    }

                    while (this.getStateVolatile() == STATE_IDLE) {
                        Thread.interrupted();
                        LockSupport.park("idling");
                    }

                    this.scheduler.waitingOrIdleRunners.remove(this.waitState);
                    this.waitState = null;
                }

                final int currState = this.compareAndExchangeStateVolatile(STATE_INTERRUPT, STATE_WAITING);
                if (currState == STATE_HALTED) {
                    return;
                } else if (currState != STATE_INTERRUPT) {
                    throw new IllegalStateException("State must be HALTED or INTERRUPT at beginning of run loop");
                }

                while (this.getStateVolatile() == STATE_WAITING) {
                    final ScheduledTickTask toTick = this.waitForTick();

                    if (toTick == null) {
                        break;
                    }

                    if (!this.moveToTickingState()) {
                        final SchedulableTick tick = toTick.getTick();
                        if (tick != null) {
                            this.scheduler.insert(tick, tick.hasTasks());
                        }
                        break;
                    }

                    this.doTick(toTick);

                    if (STATE_TICKING != this.compareAndExchangeStateVolatile(STATE_TICKING, STATE_WAITING)) {
                        break;
                    }
                }
            }
        }

        private void begin() {
            this.setupWaitState(DEADLINE_NOT_SET);
        }

        private void die() {
            this.cleanupWatch(false);
            if (this.waitState != null) {
                this.scheduler.waitingOrIdleRunners.remove(this.waitState);
                this.waitState = null;
            }
            this.scheduler.aliveThreads.remove(this);
            if (this.getStateVolatile() == STATE_HALTED) {
                this.scheduler.interruptAllRunners();
            }
        }

        @Override
        public void run() {
            try {
                this.begin();
                this.doRun();
            } finally {
                this.die();
            }
        }
    }

    private static final class ScheduledTickTask {
        private static final Comparator<ScheduledTickTask> TICK_COMPARATOR = (final ScheduledTickTask t1, final ScheduledTickTask t2) -> {
            final int timeCmp = TimeUtil.compareTimes(t1.tickStart, t2.tickStart);
            if (timeCmp != 0) {
                return timeCmp;
            }
            final SchedulableTick tick1 = t1.getTick();
            final SchedulableTick tick2 = t2.getTick();
            if (tick1 == null || tick2 == null) {
                return 0;
            }
            return Long.signum(tick1.id - tick2.id);
        };
        private static final Comparator<ScheduledTickTask> TASK_COMPARATOR = (final ScheduledTickTask t1, final ScheduledTickTask t2) -> {
            final int timeCmp = TimeUtil.compareTimes(t1.lastTaskNotify, t2.lastTaskNotify);
            if (timeCmp != 0) {
                return timeCmp;
            }
            final SchedulableTick tick1 = t1.getTick();
            final SchedulableTick tick2 = t2.getTick();
            if (tick1 == null || tick2 == null) {
                return 0;
            }
            return Long.signum(tick1.id - tick2.id);
        };

        private final WeakReference<SchedulableTick> tickRef;
        private final long tickStart;
        private long lastTaskNotify;
        private final TickThreadRunner owner;

        private volatile boolean taken;
        private static final VarHandle TAKEN_HANDLE = ConcurrentUtil.getVarHandle(ScheduledTickTask.class, "taken", boolean.class);
        private volatile boolean watched;
        private static final VarHandle WATCHED_HANDLE = ConcurrentUtil.getVarHandle(ScheduledTickTask.class, "watched", boolean.class);

        private ScheduledTickTask(final SchedulableTick tick, final long tickStart, final long lastTaskNotify,
                                  final TickThreadRunner owner) {
            this.tickRef = new WeakReference<>(tick);
            this.tickStart = tickStart;
            this.lastTaskNotify = lastTaskNotify;
            this.owner = owner;
        }

        public SchedulableTick getTick() {
            return this.tickRef.get();
        }

        public boolean take() {
            return !(boolean)TAKEN_HANDLE.getVolatile(this) && !(boolean)TAKEN_HANDLE.compareAndExchange(this, false, true);
        }

        public boolean isTaken() {
            return (boolean)TAKEN_HANDLE.getVolatile(this);
        }

        public boolean watch() {
            return !(boolean)WATCHED_HANDLE.getVolatile(this) && !(boolean)WATCHED_HANDLE.compareAndExchange(this, false, true);
        }

        public boolean unwatch() {
            return (boolean)WATCHED_HANDLE.compareAndExchange(this, true, false);
        }

        public boolean isWatched() {
            return (boolean)WATCHED_HANDLE.getVolatile(this);
        }

        public long getLastTaskNotify() {
            return this.lastTaskNotify;
        }

        public void setLastTaskNotify(final long value) {
            this.lastTaskNotify = value;
        }
    }
}
