package java.util.concurrent;

public class ThreadPoolExecutor extends AbstractExecutorService {

    //高3位：表示当前线程池运行状态   除去高3位之后的低位：表示当前线程池中所拥有的线程数量
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    //表示在ctl中，低COUNT_BITS位 是用于存放当前线程数量的位。
    private static final int COUNT_BITS = Integer.SIZE - 3;
    //低COUNT_BITS位 所能表达的最大数值。 000 11111111111111111111 => 5亿多。
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    /*运行状态在高3位放置*/
    //111 000000000000000000  转换成整数，其实是一个负数
    private static final int RUNNING    = -1 << COUNT_BITS;
    //000 000000000000000000
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    //001 000000000000000000
    private static final int STOP       =  1 << COUNT_BITS;
    //010 000000000000000000
    private static final int TIDYING    =  2 << COUNT_BITS;
    //011 000000000000000000
    private static final int TERMINATED =  3 << COUNT_BITS;

    // Packing and unpacking ctl
    //获取当前线程池运行状态
    //~000 11111111111111111111 => 111 000000000000000000000
    //c == ctl = 111 000000000000000000111
    //111 000000000000000000111
    //111 000000000000000000000
    //111 000000000000000000000
    private static int runStateOf(int c)     { return c & ~CAPACITY; }

    //获取当前线程池线程数量
    //c == ctl = 111 000000000000000000111
    //111 000000000000000000111
    //000 111111111111111111111
    //000 000000000000000000111 => 7
    private static int workerCountOf(int c)  { return c & CAPACITY; }

    //用在重置当前线程池ctl值时  会用到
    //rs 表示线程池状态   wc 表示当前线程池中worker（线程）数量
    //111 000000000000000000
    //000 000000000000000111
    //111 000000000000000111
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */
    //比较当前线程池ctl所表示的状态，是否小于某个状态s
    //c = 111 000000000000000111 <  000 000000000000000000 == true
    //所有情况下，RUNNING < SHUTDOWN < STOP < TIDYING < TERMINATED
    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    //比较当前线程池ctl所表示的状态，是否大于等于某个状态s
    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    //小于SHUTDOWN 的一定是RUNNING。 SHUTDOWN == 0
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * Attempts to CAS-increment the workerCount field of ctl.
     */
    //使用CAS方式 让ctl值+1 ，成功返回true, 失败返回false
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * Attempts to CAS-decrement the workerCount field of ctl.
     */
    //使用CAS方式 让ctl值-1 ，成功返回true, 失败返回false
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    //将ctl值减一，这个方法一定成功
    private void decrementWorkerCount() {
        //这里会一直重试，直到成功为止。
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    //任务队列，当线程池中的线程达到核心线程数量时，再提交任务 就会直接提交到 workQueue
    //workQueue  instanceOf ArrayBrokingQueue   LinkedBrokingQueue  同步队列
    private final BlockingQueue<Runnable> workQueue;

    //线程池全局锁，增加worker 减少 worker 时需要持有mainLock ， 修改线程池运行状态时，也需要。
    private final ReentrantLock mainLock = new ReentrantLock();

    //线程池中真正存放 worker->thread 的地方。
    private final HashSet<Worker> workers = new HashSet<Worker>();

    //当外部线程调用  awaitTermination() 方法时，外部线程会等待当前线程池状态为 Termination 为止。
    //等待是如何实现的？ 就是将外部线程 封装成 waitNode 放入到 Condition 队列中了， waitNode.Thread 就是外部线程，会被park掉（处于WAITING状态）。
    //当线程池 状态 变为 Termination时，会去唤醒这些线程。通过 termination.signalAll() ，唤醒之后这些线程会进入到 阻塞队列，然后头结点会去抢占mainLock。
    //抢占到的线程，会继续执行awaitTermination() 后面程序。这些线程最后，都会正常执行。
    //简单理解：termination.await() 会将线程阻塞在这。
    //         termination.signalAll() 会将阻塞在这的线程依次唤醒
    private final Condition termination = mainLock.newCondition();

    //记录线程池生命周期内 线程数最大值
    private int largestPoolSize;

    //记录线程池所完成任务总数 ，当worker退出时会将 worker完成的任务累积到completedTaskCount
    private long completedTaskCount;

    //创建线程时会使用 线程工厂，当我们使用 Executors.newFix...  newCache... 创建线程池时，使用的是 DefaultThreadFactory
    //一般不建议使用Default线程池，推荐自己实现ThreadFactory
    private volatile ThreadFactory threadFactory;

    //拒绝策略，juc包提供了4中方式，默认采用 Abort..抛出异常的方式。
    private volatile RejectedExecutionHandler handler;

    //空闲线程存活时间，当allowCoreThreadTimeOut == false 时，会维护核心线程数量内的线程存活，超出部分会被超时。
    //allowCoreThreadTimeOut == true 核心数量内的线程 空闲时 也会被回收。
    private volatile long keepAliveTime;

    //控制核心线程数量内的线程 是否可以被回收。true 可以，false不可以。
    private volatile boolean allowCoreThreadTimeOut;

    //核心线程数量限制。
    private volatile int corePoolSize;

    //线程池最大线程数量限制。
    private volatile int maximumPoolSize;

    //缺省拒绝策略，采用的是AbortPolicy 抛出异常的方式。
    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");

    /* The context to be used when executing the finalizer, or null. */
    private final AccessControlContext acc;

    private final class Worker
            extends AbstractQueuedSynchronizer
            implements Runnable
    {
        //Worker采用了AQS的独占模式
        //独占模式：两个重要属性  state  和  ExclusiveOwnerThread
        //state：0时表示未被占用 > 0时表示被占用   < 0 时 表示初始状态，这种情况下不能被抢锁。
        //ExclusiveOwnerThread:表示独占锁的线程。
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** Thread this worker is running in.  Null if factory fails. */
        //worker内部封装的工作线程
        final Thread thread;
        /** Initial task to run.  Possibly null. */
        //假设firstTask不为空，那么当worker启动后（内部的线程启动)会优先执行firstTask，当执行完firstTask后，会到queue中去获取下一个任务。
        Runnable firstTask;

        /** Per-thread task counter */
        //记录当前worker所完成任务数量。
        volatile long completedTasks;

        /**
         * Creates with given first task and thread from ThreadFactory.
         * @param firstTask the first task (null if none)
         */
        //firstTask可以为null。为null 启动后会到queue中获取。
        Worker(Runnable firstTask) {
            //设置AQS独占模式为初始化中状态，这个时候 不能被抢占锁。
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            //使用线程工厂创建了一个线程，并且将当前worker 指定为 Runnable，也就是说当thread启动的时候，会以worker.run()为入口。
            this.thread = getThreadFactory().newThread(this);
        }

        /** Delegates main run loop to outer runWorker  */
        //当worker启动时，会执行run()
        public void run() {
            //ThreadPoolExecutor->runWorker() 这个是核心方法，等后面分析worker启动后逻辑时会以这里切入。
            runWorker(this);
        }

        // Lock methods
        //
        // The value 0 represents the unlocked state.
        // The value 1 represents the locked state.
        //判断当前worker的独占锁是否被独占。
        //0 表示未被占用
        //1 表示已占用
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }


        //尝试去占用worker的独占锁
        //返回值 表示是否抢占成功
        protected boolean tryAcquire(int unused) {
            //使用CAS修改 AQS中的 state ，期望值为0(0时表示未被占用），修改成功表示当前线程抢占成功
            //那么则设置 ExclusiveOwnerThread 为当前线程。
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }

            return false;
        }

        //外部不会直接调用这个方法 这个方法是AQS 内调用的，外部调用unlock时 ，unlock->AQS.release() ->tryRelease()
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        //加锁，加锁失败时，会阻塞当前线程，直到获取到锁位置。
        public void lock()        { acquire(1); }

        //尝试去加锁，如果当前锁是未被持有状态，那么加锁成功后 会返回true，否则不会阻塞当前线程，直接返回false.
        public boolean tryLock()  { return tryAcquire(1); }

        //一般情况下，咱们调用unlock 要保证 当前线程是持有锁的。
        //特殊情况，当worker的state == -1 时，调用unlock 表示初始化state 设置state == 0
        //启动worker之前会先调用unlock()这个方法。会强制刷新ExclusiveOwnerThread == null State==0
        public void unlock()      { release(1); }

        //就是返回当前worker的lock是否被占用。
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    private void advanceRunState(int targetState) {
        //自旋
        for (;;) {
            int c = ctl.get();
            //条件成立：假设targetState == SHUTDOWN，说明 当前线程池状态是 >= SHUTDOWN
            //条件不成立：假设targetState == SHUTDOWN ，说明当前线程池状态是RUNNING。
            if (runStateAtLeast(c, targetState) ||
                    ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    final void tryTerminate() {
        //自旋
        for (;;) {
            //获取最新ctl值
            int c = ctl.get();
            //条件一：isRunning(c)  成立，直接返回就行，线程池很正常！
            //条件二：runStateAtLeast(c, TIDYING) 说明 已经有其它线程 在执行 TIDYING -> TERMINATED状态了,当前线程直接回去。
            //条件三：(runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty())
            //SHUTDOWN特殊情况，如果是这种情况，直接回去。得等队列中的任务处理完毕后，再转化状态。
            if (isRunning(c) ||
                    runStateAtLeast(c, TIDYING) ||
                    (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;

            //什么情况会执行到这里？
            //1.线程池状态 >= STOP
            //2.线程池状态为 SHUTDOWN 且 队列已经空了

            //条件成立：当前线程池中的线程数量 > 0
            if (workerCountOf(c) != 0) { // Eligible to terminate
                //中断一个空闲线程。
                //空闲线程，在哪空闲呢？ queue.take() | queue.poll()
                //1.唤醒后的线程 会在getTask()方法返回null
                //2.执行退出逻辑的时候会再次调用tryTerminate() 唤醒下一个空闲线程
                //3.因为线程池状态是 （线程池状态 >= STOP || 线程池状态为 SHUTDOWN 且 队列已经空了） 最终调用addWorker时，会失败。
                //最终空闲线程都会在这里退出，非空闲线程 当执行完当前task时，也会调用tryTerminate方法，有可能会走到这里。
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            //执行到这里的线程是谁？
            //workerCountOf(c) == 0 时，会来到这里。
            //最后一个退出的线程。 咱们知道，在 （线程池状态 >= STOP || 线程池状态为 SHUTDOWN 且 队列已经空了）
            //线程唤醒后，都会执行退出逻辑，退出过程中 会 先将 workerCount计数 -1 => ctl -1。
            //调用tryTerminate 方法之前，已经减过了，所以0时，表示这是最后一个退出的线程了。

            final ReentrantLock mainLock = this.mainLock;
            //获取线程池全局锁
            mainLock.lock();
            try {
                //设置线程池状态为TIDYING状态。
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        //调用钩子方法
                        terminated();
                    } finally {
                        //设置线程池状态为TERMINATED状态。
                        ctl.set(ctlOf(TERMINATED, 0));
                        //唤醒调用 awaitTermination() 方法的线程。
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                //释放线程池全局锁。
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        //获取线程池全局锁
        mainLock.lock();
        try {
            //遍历所有worker
            for (Worker w : workers)
                //interruptIfStarted() 如果worker内的thread 是启动状态，则给它一个中断信号。。
                w.interruptIfStarted();
        } finally {
            //释放线程池全局锁
            mainLock.unlock();
        }
    }

    //onlyOne == true 说明只中断一个线程 ，false 则中断所有线程
    //共同前提，worker是空闲状态。
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        //持有全局锁
        mainLock.lock();
        try {
            //迭代所有worker
            for (Worker w : workers) {
                //获取当前worker的线程 保存到t
                Thread t = w.thread;
                //条件一：条件成立：!t.isInterrupted()  == true  说明当前迭代的这个线程尚未中断。
                //条件二：w.tryLock() 条件成立：说明当前worker处于空闲状态，可以去给它一个中断信号。 目前worker内的线程 在 queue.take() | queue.poll()
                //阻塞中。因为worker执行task时，是加锁的!
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        //给当前线程中断信号..处于queue阻塞的线程，会被唤醒，唤醒后，进入下一次自旋时，可能会return null。执行退出相关的逻辑。
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        //释放worker的独占锁。
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }

        } finally {
            //释放全局锁。
            mainLock.unlock();
        }
    }

    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    void onShutdown() {
    }

    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    //firstTask 可以为null，表示启动worker之后，worker自动到queue中获取任务.. 如果不是null，则worker优先执行firstTask
    //core 采用的线程数限制 如果为true 采用 核心线程数限制  false采用 maximumPoolSize线程数限制.

    //返回值总结：
    //true 表示创建worker成功，且线程启动

    //false 表示创建失败。
    //1.线程池状态rs > SHUTDOWN (STOP/TIDYING/TERMINATION)
    //2.rs == SHUTDOWN 但是队列中已经没有任务了 或者 当前状态是SHUTDOWN且队列未空，但是firstTask不为null
    //3.当前线程池已经达到指定指标（coprePoolSize 或者 maximumPoolSIze）
    //4.threadFactory 创建的线程是null
    private boolean addWorker(Runnable firstTask, boolean core) {
        //自旋 判断当前线程池状态是否允许创建线程的事情。
        retry:
        for (;;) {
            //获取当前ctl值保存到c
            int c = ctl.get();
            //获取当前线程池运行状态 保存到rs长
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.

            //条件一：rs >= SHUTDOWN 成立：说明当前线程池状态不是running状态
            //条件二：前置条件，当前的线程池状态不是running状态  ! (rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty())
            //rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty()
            //表示：当前线程池状态是SHUTDOWN状态 & 提交的任务是空，addWorker这个方法可能不是execute调用的。 & 当前任务队列不是空
            //排除掉这种情况，当前线程池是SHUTDOWN状态，但是队列里面还有任务尚未处理完，这个时候是允许添加worker，但是不允许再次提交task。
            if (rs >= SHUTDOWN && ! (rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty()))
                //什么情况下回返回false?
                //线程池状态 rs > SHUTDOWN
                //rs == SHUTDOWN 但是队列中已经没有任务了 或者 rs == SHUTDOWN 且 firstTask != null
                return false;

            //上面这些代码，就是判断 当前线程池状态 是否允许添加线程。


            //内部自旋 获取创建线程令牌的过程。
            for (;;) {
                //获取当前线程池中线程数量 保存到wc中
                int wc = workerCountOf(c);

                //条件一：wc >= CAPACITY 永远不成立，因为CAPACITY是一个5亿多大的数字
                //条件二：wc >= (core ? corePoolSize : maximumPoolSize)
                //core == true ,判断当前线程数量是否>=corePoolSize，会拿核心线程数量做限制。
                //core == false,判断当前线程数量是否>=maximumPoolSize，会拿最大线程数量做限制。
                if (wc >= CAPACITY || wc >= (core ? corePoolSize : maximumPoolSize))
                    //执行到这里，说明当前无法添加线程了，已经达到指定限制了
                    return false;

                //条件成立：说明记录线程数量已经加1成功，相当于申请到了一块令牌。
                //条件失败：说明可能有其它线程，修改过ctl这个值了。
                //可能发生过什么事？
                //1.其它线程execute() 申请过令牌了，在这之前。导致CAS失败
                //2.外部线程可能调用过 shutdown() 或者 shutdownNow() 导致线程池状态发生变化了，咱们知道 ctl 高3位表示状态
                //状态改变后，cas也会失败。
                if (compareAndIncrementWorkerCount(c))
                    //进入到这里面，一定是cas成功啦！申请到令牌了
                    //直接跳出了 retry 外部这个for自旋。
                    break retry;

                //CAS失败，没有成功的申请到令牌
                //获取最新的ctl值
                c = ctl.get();  // Re-read ctl
                //判断当前线程池状态是否发生过变化,如果外部在这之前调用过shutdown. shutdownNow 会导致状态变化。
                if (runStateOf(c) != rs)
                    //状态发生变化后，直接返回到外层循环，外层循环负责判断当前线程池状态，是否允许创建线程。
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }





        //表示创建的worker是否已经启动，false未启动  true启动
        boolean workerStarted = false;
        //表示创建的worker是否添加到池子中了 默认false 未添加 true是添加。
        boolean workerAdded = false;

        //w表示后面创建worker的一个引用。
        Worker w = null;
        try {
            //创建Worker，执行完后，线程应该是已经创建好了。
            w = new Worker(firstTask);

            //将新创建的worker节点的线程 赋值给 t
            final Thread t = w.thread;

            //为什么要做 t != null 这个判断？
            //为了防止ThreadFactory 实现类有bug，因为ThreadFactory 是一个接口，谁都可以实现。
            //万一哪个 小哥哥 脑子一热，有bug，创建出来的线程 是null、、
            //Doug lea考虑的比较全面。肯定会防止他自己的程序报空指针，所以这里一定要做！
            if (t != null) {
                //将全局锁的引用保存到mainLock
                final ReentrantLock mainLock = this.mainLock;
                //持有全局锁，可能会阻塞，直到获取成功为止，同一时刻 操纵 线程池内部相关的操作，都必须持锁。
                mainLock.lock();
                //从这里加锁之后，其它线程 是无法修改当前线程池状态的。
                try {
                    //获取最新线程池运行状态保存到rs中
                    int rs = runStateOf(ctl.get());

                    //条件一：rs < SHUTDOWN 成立：最正常状态，当前线程池为RUNNING状态.
                    //条件二：前置条件：当前线程池状态不是RUNNING状态。
                    //(rs == SHUTDOWN && firstTask == null)  当前状态为SHUTDOWN状态且firstTask为空。其实判断的就是SHUTDOWN状态下的特殊情况，
                    //只不过这里不再判断队列是否为空了
                    if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
                        //t.isAlive() 当线程start后，线程isAlive会返回true。
                        //防止脑子发热的程序员，ThreadFactory创建线程返回给外部之前，将线程start了。。
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();

                        //将咱们创建的worker添加到线程池中。
                        workers.add(w);
                        //获取最新当前线程池线程数量
                        int s = workers.size();
                        //条件成立：说明当前线程数量是一个新高。更新largestPoolSize
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        //表示线程已经追加进线程池中了。
                        workerAdded = true;
                    }
                } finally {
                    //释放线程池全局锁。
                    mainLock.unlock();
                }
                //条件成立:说明 添加worker成功
                //条件失败：说明线程池在lock之前，线程池状态发生了变化，导致添加失败。
                if (workerAdded) {
                    //成功后，则将创建的worker启动，线程启动。
                    t.start();
                    //启动标记设置为true
                    workerStarted = true;
                }
            }

        } finally {
            //条件成立：! workerStarted 说明启动失败，需要做清理工作。
            if (! workerStarted)
                //失败时做什么清理工作？
                //1.释放令牌
                //2.将当前worker清理出workers集合
                addWorkerFailed(w);
        }

        //返回新创建的线程是否启动。
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation.
     * - removes worker from workers, if present
     * - decrements worker count
     * - rechecks for termination, in case the existence of this
     *   worker was holding up termination
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        //持有线程池全局锁，因为操作的是线程池相关的东西。
        mainLock.lock();
        try {
            //条件成立：需要将worker在workers中清理出去。
            if (w != null)
                workers.remove(w);
            //将线程池计数恢复-1，前面+1过，这里因为失败，所以要-1，相当于归还令牌。
            decrementWorkerCount();
            //回头讲，shutdown shutdownNow再说。
            tryTerminate();
        } finally {
            //释放线程池全局锁。
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called
     * only from worker threads. Unless completedAbruptly is set,
     * assumes that workerCount has already been adjusted to account
     * for exit.  This method removes thread from worker set, and
     * possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than
     * corePoolSize workers are running or queue is non-empty but
     * there are no workers.
     *
     * @param w the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        //条件成立：代表当前w 这个worker是发生异常退出的，task任务执行过程中向上抛出异常了..
        //异常退出时，ctl计数，并没有-1
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        //获取线程池的全局锁引用
        final ReentrantLock mainLock = this.mainLock;
        //加锁
        mainLock.lock();
        try {
            //将当前worker完成的task数量，汇总到线程池的completedTaskCount
            completedTaskCount += w.completedTasks;
            //将worker从池子中移除..
            workers.remove(w);
        } finally {
            //释放全局锁
            mainLock.unlock();
        }


        tryTerminate();

        //获取最新ctl值
        int c = ctl.get();
        //条件成立：当前线程池状态为 RUNNING 或者 SHUTDOWN状态
        if (runStateLessThan(c, STOP)) {

            //条件成立：当前线程是正常退出..
            if (!completedAbruptly) {

                //min表示线程池最低持有的线程数量
                //allowCoreThreadTimeOut == true => 说明核心线程数内的线程，也会超时被回收。 min == 0
                //allowCoreThreadTimeOut == false => min == corePoolSize
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;


                //线程池状态：RUNNING SHUTDOWN
                //条件一：假设min == 0 成立
                //条件二：! workQueue.isEmpty() 说明任务队列中还有任务，最起码要留一个线程。
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;

                //条件成立：线程池中还拥有足够的线程。
                //考虑一个问题： workerCountOf(c) >= min  =>  (0 >= 0) ?
                //有可能！
                //什么情况下？ 当线程池中的核心线程数是可以被回收的情况下，会出现这种情况，这种情况下，当前线程池中的线程数 会变为0
                //下次再提交任务时，会再创建线程。
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }

            //1.当前线程在执行task时 发生异常，这里一定要创建一个新worker顶上去。
            //2.!workQueue.isEmpty() 说明任务队列中还有任务，最起码要留一个线程。 当前状态为 RUNNING || SHUTDOWN
            //3.当前线程数量 < corePoolSize值，此时会创建线程，维护线程池数量在corePoolSize个。
            addWorker(null, false);
        }
    }

    //什么情况下会返回null？
    //1.rs >= STOP 成立说明：当前的状态最低也是STOP状态，一定要返回null了
    //2.前置条件 状态是 SHUTDOWN ，workQueue.isEmpty()
    //3.线程池中的线程数量 超过 最大限制时，会有一部分线程返回Null
    //4.线程池中的线程数超过corePoolSize时，会有一部分线程 超时后，返回null。
    private Runnable getTask() {
        //表示当前线程获取任务是否超时 默认false true表示已超时
        boolean timedOut = false; // Did the last poll() time out?

        //自旋
        for (;;) {
            //获取最新ctl值保存到c中。
            int c = ctl.get();
            //获取线程池当前运行状态
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            //条件一：rs >= SHUTDOWN 条件成立：说明当前线程池是非RUNNING状态，可能是 SHUTDOWN/STOP....
            //条件二：(rs >= STOP || workQueue.isEmpty())
            //2.1:rs >= STOP 成立说明：当前的状态最低也是STOP状态，一定要返回null了
            //2.2：前置条件 状态是 SHUTDOWN ，workQueue.isEmpty()条件成立：说明当前线程池状态为SHUTDOWN状态 且 任务队列已空，此时一定返回null。
            //返回null，runWorker方法就会将返回Null的线程执行线程退出线程池的逻辑。
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                //使用CAS+死循环的方式让 ctl值 -1
                decrementWorkerCount();
                return null;
            }

            //执行到这里，有几种情况？
            //1.线程池是RUNNING状态
            //2.线程池是SHUTDOWN状态 但是队列还未空，此时可以创建线程。

            //获取线程池中的线程数量
            int wc = workerCountOf(c);

            // Are workers subject to culling?
            //timed == true 表示当前这个线程 获取 task 时 是支持超时机制的，使用queue.poll(xxx,xxx); 当获取task超时的情况下，下一次自旋就可能返回null了。
            //timed == false 表示当前这个线程 获取 task 时 是不支持超时机制的，当前线程会使用 queue.take();

            //情况1：allowCoreThreadTimeOut == true 表示核心线程数量内的线程 也可以被回收。
            //所有线程 都是使用queue.poll(xxx,xxx) 超时机制这种方式获取task.
            //情况2：allowCoreThreadTimeOut == false 表示当前线程池会维护核心数量内的线程。
            //wc > corePoolSize
            //条件成立：当前线程池中的线程数量是大于核心线程数的，此时让所有路过这里的线程，都是用poll 支持超时的方式去获取任务，
            //这样，就会可能有一部分线程获取不到任务，获取不到任务 返回Null，然后..runWorker会执行线程退出逻辑。
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;


            //条件一：(wc > maximumPoolSize || (timed && timedOut))
            //1.1：wc > maximumPoolSize  为什么会成立？setMaximumPoolSize()方法，可能外部线程将线程池最大线程数设置为比初始化时的要小
            //1.2: (timed && timedOut) 条件成立：前置条件，当前线程使用 poll方式获取task。上一次循环时  使用poll方式获取任务时，超时了
            //条件一 为 true 表示 线程可以被回收，达到回收标准，当确实需要回收时再回收。

            //条件二：(wc > 1 || workQueue.isEmpty())
            //2.1: wc > 1  条件成立，说明当前线程池中还有其他线程，当前线程可以直接回收，返回null
            //2.2: workQueue.isEmpty() 前置条件 wc == 1， 条件成立：说明当前任务队列 已经空了，最后一个线程，也可以放心的退出。
            if ((wc > maximumPoolSize || (timed && timedOut))
                    && (wc > 1 || workQueue.isEmpty())) {
                //使用CAS机制 将 ctl值 -1 ,减1成功的线程，返回null
                //CAS成功的，返回Null
                //CAS失败？ 为什么会CAS失败？
                //1.其它线程先你一步退出了
                //2.线程池状态发生变化了。
                if (compareAndDecrementWorkerCount(c))
                    return null;
                //再次自旋时，timed有可能就是false了，因为当前线程cas失败，很有可能是因为其它线程成功退出导致的，再次咨询时
                //检查发现，当前线程 就可能属于 不需要回收范围内了。
                continue;
            }

            try {
                //获取任务的逻辑
                Runnable r = timed ?
                        workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                        workQueue.take();

                //条件成立：返回任务
                if (r != null)
                    return r;

                //说明当前线程超时了...
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /*
    * 1、初始化wt、task、独占锁lock（-1 -> 0）
    * 2、getTask从workQueue申请任务
    * 3、开启lock，
    *       1）判断线程已经不行了，STOP之后了，打标记线程中断
    *       2）钩子方法，类似事务一样，可以子类实现前置通知
    *           执行任务，run
    *       3）钩子方法，类似事务一样，可以子类实现后续通知
    * 4、解开lock（释放worker的非重入锁）
    * 5、有异常，统一最后处理，否则正常退出
    * */
    //w 就是启动worker
    final void runWorker(Worker w) {
        //wt == w.thread
        Thread wt = Thread.currentThread();
        //将初始执行task赋值给task
        Runnable task = w.firstTask;
        //清空当前w.firstTask引用
        w.firstTask = null;
        //这里为什么先调用unlock? 就是为了初始化worker state == 0 和 exclusiveOwnerThread ==null
        w.unlock(); // allow interrupts

        //是否是突然退出，true->发生异常了，当前线程是突然退出，回头需要做一些处理
        //false->正常退出。
        boolean completedAbruptly = true;

        try {
            //条件一：task != null 指的就是firstTask是不是null，如果不是null，直接执行循环体里面。
            //条件二：(task = getTask()) != null   条件成立：说明当前线程在queue中获取任务成功，getTask这个方法是一个会阻塞线程的方法
            //getTask如果返回null，当前线程需要执行结束逻辑。
            while (task != null || (task = getTask()) != null) {
                //worker设置独占锁 为当前线程
                //为什么要设置独占锁呢？shutdown时会判断当前worker状态，根据独占锁是否空闲来判断当前worker是否正在工作。
                w.lock();
                // If pool is stopping, ensure thread is interrupted;如果线程停止了，确保线程是interrupt状态
                // if not, ensure thread is not interrupted.  This requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt

                //条件一：runStateAtLeast(ctl.get(), STOP)  说明线程池目前处于STOP/TIDYING/TERMINATION 此时线程一定要给它一个中断信号
                //条件一成立：runStateAtLeast(ctl.get(), STOP)&& !wt.isInterrupted()
                //上面如果成立：说明当前线程池状态是>=STOP 且 当前线程是未设置中断状态的，此时需要进入到if里面，给当前线程一个中断。

                //假设：runStateAtLeast(ctl.get(), STOP) == false
                // (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)) 在干吗呢？
                // Thread.interrupted() 获取当前中断状态，且设置中断位为false。连续调用两次，这个interrupted()方法 第二次一定是返回false.
                // runStateAtLeast(ctl.get(), STOP) 大概率这里还是false.
                // 其实它在强制刷新当前线程的中断标记位 false，因为有可能上一次执行task时，业务代码里面将当前线程的中断标记位 设置为了 true，且没有处理
                // 这里一定要强制刷新一下。不会再影响到后面的task了。
                //假设：Thread.interrupted() == true  且 runStateAtLeast(ctl.get(), STOP)) == true
                //这种情况有发生几率么？
                //有可能，因为外部线程在 第一次 (runStateAtLeast(ctl.get(), STOP) == false 后，有机会调用shutdown 、shutdownNow方法，将线程池状态修改
                //这个时候，也会将当前线程的中断标记位 再次设置回 中断状态。
                if ((runStateAtLeast(ctl.get(), STOP) ||
                        (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP))) &&
                        !wt.isInterrupted())
                    wt.interrupt();

                try {
                    //钩子方法，留给子类实现的
                    beforeExecute(wt, task);
                    //表示异常情况，如果thrown不为空，表示 task运行过程中 向上层抛出异常了。
                    Throwable thrown = null;
                    try {
                        //task 可能是FutureTask 也可能是 普通的Runnable接口实现类。
                        //如果前面是通过submit()提交的 runnable/callable 会被封装成 FutureTask。这个不清楚，请看上一期，在b站。
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        //钩子方法，留给子类实现的
                        afterExecute(task, thrown);
                    }
                } finally {
                    //将局部变量task置为Null
                    task = null;
                    //更新worker完成任务数量
                    w.completedTasks++;

                    //worker处理完一个任务后，会释放掉独占锁
                    //1.正常情况下，会再次回到getTask()那里获取任务  while(getTask...)
                    //2.task.run()时内部抛出异常了..
                    w.unlock();
                }
            }

            //什么情况下，会来到这里？
            //getTask()方法返回null时，说明当前线程应该执行退出逻辑了。
            completedAbruptly = false;
        } finally {
            //task.run()内部抛出异常时，直接从 w.unlock() 那里 跳到这一行。
            //正常退出 completedAbruptly == false
            //异常退出 completedAbruptly == true
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory and rejected execution handler.
     * It may be more convenient to use one of the {@link Executors} factory
     * methods instead of this general purpose constructor.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                Executors.defaultThreadFactory(), defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default rejected execution handler.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                threadFactory, defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                Executors.defaultThreadFactory(), handler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,//核心线程数限制
                              int maximumPoolSize,//最大线程限制
                              long keepAliveTime,//空闲线程存活时间
                              TimeUnit unit,//时间单位 seconds nano..
                              BlockingQueue<Runnable> workQueue,//任务队列
                              ThreadFactory threadFactory,//线程工厂
                              RejectedExecutionHandler handler/*拒绝策略*/) {
        //判断参数是否越界
        if (corePoolSize < 0 ||
                maximumPoolSize <= 0 ||
                maximumPoolSize < corePoolSize ||
                keepAliveTime < 0)
            throw new IllegalArgumentException();

        //工作队列 和 线程工厂 和 拒绝策略 都不能为空。
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();


        this.acc = System.getSecurityManager() == null ?
                null :
                AccessController.getContext();

        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * 【1】
     * 接收任务的方法，
     * command 可以是普通的Runnable 实现类，也可以是 FutureTask
     */
    public void execute(Runnable command) {
        //非空判断..
        if (command == null)
            throw new NullPointerException();

        //获取ctl最新值赋值给c，ctl ：高3位 表示线程池状态，低位表示当前线程池线程数量。
        int c = ctl.get();
        //workerCountOf(c) 获取出当前线程数量
        //条件成立：表示当前线程数量小于核心线程数，此次提交任务，直接创建一个新的worker，对应线程池中多了一个新的线程。
        if (workerCountOf(c) < corePoolSize) {
            //addWorker 即为创建线程的过程，会创建worker对象，并且将command作为firstTask
            //core == true 表示采用核心线程数量限制  false表示采用 maximumPoolSize
            if (addWorker(command, true))
                //创建成功后，直接返回。addWorker方法里面会启动新创建的worker，将firstTask执行。
                return;

            //执行到这条语句，说明addWorker一定是失败了...
            //有几种可能呢？？
            //1.存在并发现象，execute方法是可能有多个线程同时调用的，当workerCountOf(c) < corePoolSize成立后，
            //其它线程可能也成立了，并且向线程池中创建了worker。这个时候线程池中的核心线程数已经达到，所以...
            //2.当前线程池状态发生改变了。 RUNNING SHUTDOWN STOP TIDYING　TERMINATION
            //当线程池状态是非RUNNING状态时，addWorker(firstTask!=null, true|false) 一定会失败。
            //SHUTDOWN 状态下，也有可能创建成功。前提 firstTask == null 而且当前 queue  不为空。特殊情况。
            c = ctl.get();
        }


        //执行到这里有几种情况？
        //1.当前线程数量已经达到corePoolSize
        //2.addWorker失败..

        //条件成立：说明当前线程池处于running状态，则尝试将 task 放入到workQueue中。
        if (isRunning(c) && workQueue.offer(command)) {
            //执行到这里，说明offer提交任务成功了..

            //再次获取ctl保存到recheck。
            int recheck = ctl.get();

            //条件一：! isRunning(recheck) 成立：说明你提交到队列之后，线程池状态被外部线程给修改 比如：shutdown() shutdownNow()
            //这种情况 需要把刚刚提交的任务删除掉。
            //条件二：remove(command) 有可能成功，也有可能失败
            //成功：提交之后，线程池中的线程还未消费（处理）
            //失败：提交之后，在shutdown() shutdownNow()之前，就被线程池中的线程 给处理。
            if (! isRunning(recheck) && remove(command))
                //提交之后线程池状态为 非running 且 任务出队成功，走个拒绝策略。
                reject(command);

            //有几种情况会到这里？
            //1.当前线程池是running状态(这个概率最大)
            //2.线程池状态是非running状态 但是remove提交的任务失败.

            //担心 当前线程池是running状态，但是线程池中的存活线程数量是0，这个时候，如果是0的话，会很尴尬，任务没线程去跑了,
            //这里其实是一个担保机制，保证线程池在running状态下，最起码得有一个线程在工作。
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }

        //执行到这里，有几种情况？
        //1.offer失败
        //2.当前线程池是非running状态

        //1.offer失败，需要做什么？ 说明当前queue 满了！这个时候 如果当前线程数量尚未达到maximumPoolSize的话，会创建新的worker直接执行command
        //假设当前线程数量达到maximumPoolSize的话，这里也会失败，也走拒绝策略。

        //2.线程池状态为非running状态，这个时候因为 command != null addWorker 一定是返回false。
        else if (!addWorker(command, false))
            reject(command);

    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        //获取线程池全局锁
        mainLock.lock();
        try {
            checkShutdownAccess();
            //设置线程池状态为SHUTDOWN
            advanceRunState(SHUTDOWN);
            //中断空闲线程
            interruptIdleWorkers();
            //空方法，子类可以扩展
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            //释放线程池全局锁
            mainLock.unlock();
        }
        //回头说..
        tryTerminate();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution. These tasks are drained (removed)
     * from the task queue upon return from this method.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        //返回值引用
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        //获取线程池全局锁
        mainLock.lock();
        try {
            checkShutdownAccess();
            //设置线程池状态为STOP
            advanceRunState(STOP);
            //中断线程池中所有线程
            interruptWorkers();
            //导出未处理的task
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }

        tryTerminate();
        //返回当前任务队列中 未处理的任务。
        return tasks;
    }

    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating
     * after {@link #shutdown} or {@link #shutdownNow} but has not
     * completely terminated.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not
     * to properly terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        } else {
            PrivilegedAction<Void> pa = () -> { shutdown(); return null; };
            AccessController.doPrivileged(pa, acc);
        }
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * Sets the core number of threads.  This overrides any value set
     * in the constructor.  If the new value is smaller than the
     * current value, excess existing threads will be terminated when
     * they next become idle.  If larger, new threads will, if needed,
     * be started to execute any queued tasks.
     *
     * @param corePoolSize the new core size
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @see #getCorePoolSize
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * Returns the core number of threads.
     *
     * @return the core number of threads
     * @see #setCorePoolSize
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * Starts a core thread, causing it to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed. This method will return {@code false}
     * if all core threads have already been started.
     *
     * @return {@code true} if a thread was started
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
                addWorker(null, true);
    }

    /**
     * Same as prestartCoreThread except arranges that at least one
     * thread is started even if corePoolSize is 0.
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * Starts all core threads, causing them to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed.
     *
     * @return the number of threads started
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and
     * terminate if no tasks arrive within the keepAlive time, being
     * replaced if needed when new tasks arrive. When true, the same
     * keep-alive policy applying to non-core threads applies also to
     * core threads. When false (the default), core threads are never
     * terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out,
     *         else {@code false}
     *
     * @since 1.6
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and
     * terminate if no tasks arrive within the keep-alive time, being
     * replaced if needed when new tasks arrive. When false, core
     * threads are never terminated due to lack of incoming
     * tasks. When true, the same keep-alive policy applying to
     * non-core threads applies also to core threads. To avoid
     * continual thread replacement, the keep-alive time must be
     * greater than zero when setting {@code true}. This method
     * should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true}
     *         and the current keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value)
                interruptIdleWorkers();
        }
    }

    /**
     * Sets the maximum allowed number of threads. This overrides any
     * value set in the constructor. If the new value is smaller than
     * the current value, excess existing threads will be
     * terminated when they next become idle.
     *
     * @param maximumPoolSize the new maximum
     * @throws IllegalArgumentException if the new maximum is
     *         less than or equal to zero, or
     *         less than the {@linkplain #getCorePoolSize core pool size}
     * @see #getMaximumPoolSize
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * Returns the maximum allowed number of threads.
     *
     * @return the maximum allowed number of threads
     * @see #setMaximumPoolSize
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Sets the time limit for which threads may remain idle before
     * being terminated.  If there are more than the core number of
     * threads currently in the pool, after waiting this amount of
     * time without processing a task, excess threads will be
     * terminated.  This overrides any value set in the constructor.
     *
     * @param time the time to wait.  A time value of zero will cause
     *        excess threads to terminate immediately after executing tasks.
     * @param unit the time unit of the {@code time} argument
     * @throws IllegalArgumentException if {@code time} less than zero or
     *         if {@code time} is zero and {@code allowsCoreThreadTimeOut}
     * @see #getKeepAliveTime(TimeUnit)
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * Returns the thread keep-alive time, which is the amount of time
     * that threads in excess of the core pool size may remain
     * idle before being terminated.
     *
     * @param unit the desired time unit of the result
     * @return the time limit
     * @see #setKeepAliveTime(long, TimeUnit)
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * Returns the task queue used by this executor. Access to the
     * task queue is intended primarily for debugging and monitoring.
     * This queue may be in active use.  Retrieving the task queue
     * does not prevent queued tasks from executing.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * Removes this task from the executor's internal queue if it is
     * present, thus causing it not to be run if it has not already
     * started.
     *
     * <p>This method may be useful as one part of a cancellation
     * scheme.  It may fail to remove tasks that have been converted
     * into other forms before being placed on the internal queue. For
     * example, a task entered using {@code submit} might be
     * converted into a form that maintains {@code Future} status.
     * However, in such cases, method {@link #purge} may be used to
     * remove those Futures that have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * Tries to remove from the work queue all {@link Future}
     * tasks that have been cancelled. This method can be useful as a
     * storage reclamation operation, that has no other impact on
     * functionality. Cancelled tasks are never executed, but may
     * accumulate in work queues until worker threads can actively
     * remove them. Invoking this method instead tries to remove them now.
     * However, this method may fail to remove tasks in
     * the presence of interference by other threads.
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * Returns the current number of threads in the pool.
     *
     * @return the number of threads
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                    : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate number of threads that are actively
     * executing tasks.
     *
     * @return the number of threads
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the largest number of threads that have ever
     * simultaneously been in the pool.
     *
     * @return the number of threads
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have ever been
     * scheduled for execution. Because the states of tasks and
     * threads may change dynamically during computation, the returned
     * value is only an approximation.
     *
     * @return the number of tasks
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have
     * completed execution. Because the states of tasks and threads
     * may change dynamically during computation, the returned value
     * is only an approximation, but one that does not ever decrease
     * across successive calls.
     *
     * @return the number of tasks
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state and estimated worker and
     * task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                        "Shutting down"));
        return super.toString() +
                "[" + rs +
                ", pool size = " + nworkers +
                ", active threads = " + nactive +
                ", queued tasks = " + workQueue.size() +
                ", completed tasks = " + ncompleted +
                "]";
    }

    /* Extension hooks */

    /**
     * Method invoked prior to executing the given Runnable in the
     * given thread.  This method is invoked by thread {@code t} that
     * will execute task {@code r}, and may be used to re-initialize
     * ThreadLocals, or to perform logging.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.beforeExecute} at the end of
     * this method.
     *
     * @param t the thread that will run task {@code r}
     * @param r the task that will be executed
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * Method invoked upon completion of execution of the given Runnable.
     * This method is invoked by the thread that executed the task. If
     * non-null, the Throwable is the uncaught {@code RuntimeException}
     * or {@code Error} that caused execution to terminate abruptly.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.afterExecute} at the
     * beginning of this method.
     *
     * <p><b>Note:</b> When actions are enclosed in tasks (such as
     * {@link FutureTask}) either explicitly or via methods such as
     * {@code submit}, these task objects catch and maintain
     * computational exceptions, and so they do not cause abrupt
     * termination, and the internal exceptions are <em>not</em>
     * passed to this method. If you would like to trap both kinds of
     * failures in this method, you can further probe for such cases,
     * as in this sample subclass that prints either the direct cause
     * or the underlying exception if a task has been aborted:
     *
     *  <pre> {@code
     * class ExtendedExecutor extends ThreadPoolExecutor {
     *   // ...
     *   protected void afterExecute(Runnable r, Throwable t) {
     *     super.afterExecute(r, t);
     *     if (t == null && r instanceof Future<?>) {
     *       try {
     *         Object result = ((Future<?>) r).get();
     *       } catch (CancellationException ce) {
     *           t = ce;
     *       } catch (ExecutionException ee) {
     *           t = ee.getCause();
     *       } catch (InterruptedException ie) {
     *           Thread.currentThread().interrupt(); // ignore/reset
     *       }
     *     }
     *     if (t != null)
     *       System.out.println(t);
     *   }
     * }}</pre>
     *
     * @param r the runnable that has completed
     * @param t the exception that caused termination, or null if
     * execution completed normally
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * Method invoked when the Executor has terminated.  Default
     * implementation does nothing. Note: To properly nest multiple
     * overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * A handler for rejected tasks that runs the rejected task
     * directly in the calling thread of the {@code execute} method,
     * unless the executor has been shut down, in which case the task
     * is discarded.
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        /**
         * Executes task r in the caller's thread, unless the executor
         * has been shut down, in which case the task is discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * A handler for rejected tasks that throws a
     * {@code RejectedExecutionException}.
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * Creates an {@code AbortPolicy}.
         */
        public AbortPolicy() { }

        /**
         * Always throws RejectedExecutionException.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         * @throws RejectedExecutionException always
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    " rejected from " +
                    e.toString());
        }
    }

    /**
     * A handler for rejected tasks that silently discards the
     * rejected task.
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * Does nothing, which has the effect of discarding task r.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * A handler for rejected tasks that discards the oldest unhandled
     * request and then retries {@code execute}, unless the executor
     * is shut down, in which case the task is discarded.
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardOldestPolicy} for the given executor.
         */
        public DiscardOldestPolicy() { }

        /**
         * Obtains and ignores the next task that the executor
         * would otherwise execute, if one is immediately available,
         * and then retries execution of task r, unless the executor
         * is shut down, in which case task r is instead discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
