## 线程池原理

线程池工作原理（优点，缺点）

没有线程池的时候，与有线程池的时候进行对比。解决了资源分配问题，池化思想，最大化收益最小化风险，统一资源进行管理的方式。



线程池5个状态（run、shutdown、Stop、dying、terminated）

线程池状态转换（shutdown、shutdownNow、workerQueue的变化）

7个参数（core、max、keepAliveTime、unit、workerQueue、factory、rejectHandler）

4个拒绝策略（ACDD）



## 基本方法

构造方法->7参数

小功能函数，很多围绕着ctl来进行操作的

runStateOf、workerCountOf、ctlOf

Worker包装类，run()



## 核心方法

### execute

> 提交任务流程：接任务的方法

```txt
1、任务是commond，线程池里面进来一个请求
2、判断当前工人数是否满足小于core，调用addWorker(command, true)
	成功，直接返回
	失败，可能是并发的问题，其他线程已经完成此任务；or 线程状态改变，非running状态
3、尝试放入workerQueue
	1）状态被修改非running，移除当前的任务，执行拒绝策略
		成功：该任务还没有被消费（还在workerQue中）
		失败：提交之后，在shutdown() shutdownNow()之前，就被线程池中的线程 给处理。
	2）还是running状态，判断ctl线程数为0，addWorker，最起码有一个线程在工作中
4、最后一次直接addWorker(command, false)，使用max判断
	失败：reject(command)
```



worker是实例对象还是任务？



### addWorker

> 添加新的worker，新添新丁的方法
>
> 第一部分：两个for自旋判断线程状态和worker数量，拿令牌的过程
>
> 第二部分：new Worker(firstTask);进来的任务，线程数量由线程池自己管理

shutdown状态，workerQueue任务队列有任务，即将执行的任务为null => 可以添加新的worker去执行线程



```txt
1、判断状态：
	1）running状态  √
	2）shutdown，workerQueue非空，firstTask为空  √
2、判断woker数，workerCount
	1）wc超过capacity、超过core或max  => return false
	2）wc以cas方式加1尝试获取令牌
		成功：拿到令牌，进入下一个流程
		失败：当前线程状态变化，需要重试（重新判断状态是否符合要求）
3、new Worker(firstTask)，就有了线程，后续还会对这个worker是否合法进行判断。
4、使用mainLock全局锁开始
	判断线程池是否① running状态、② shutdown且任务队列有活而且没有新东西进来。
	没问题workers Set加入这个worker进去工作。
	释放锁……
5、最后worker加入set失败的善后工作。mainLock
	1）对ctl减一
	2）set将worker清除出去
```







### runWorker

worker启动

```txt
1、初始化wt、task、独占锁lock（-1 -> 0）
2、getTask从workQueue申请任务
3、开启lock，
      1）判断线程已经不行了，STOP之后了，打标记线程中断
      2）钩子方法，类似事务一样，可以子类实现前置通知
          执行任务，run
      3）钩子方法，类似事务一样，可以子类实现后续通知
4、解开lock（释放worker的非重入锁）
5、有异常，统一最后处理，否则正常退出
```



注意：

1、processWorkerExit()方法，销毁线程。

2、while循环不断地通过getTask()方法获取任务。

3、不干活线程就阻塞，除非线程池准备收工





### getTask

> 1、从workerQueue中获取任务流程；2、也负责回收线程
>
> 一般都是正常拿到任务，有几种情况拿不到想要的结果（null）

```java
返回null的几种情况：（State、workerCount）
    1、rs >= STOP 
    2、shutdown且workerQueue为空
    3、wc > max
    4、wc > core && 超时
    
超时因素：
    allowCoreTimeOut
    cas方式回收线程（wc太大、当前任务超时）
```





### processWorkerExit

> worker退出流程，在runWorker中最后总会调用到。
>
> 由于引起线程销毁的可能性有很多，线程池还要判断是什么引发了这次销毁，是否要改变线程池的现阶段状态，是否要根据新状态，重新分配线程。

```java
private void processWorkerExit(Worker w, boolean completedAbruptly) {
1、正常 or 异常退出
    1）异常退出：ctl-1
    2）正常退出：拿mainLock；set移除该worker，记录完成task数量
2、tryTerminate();  
    进入到关闭线程池的逻辑
3、正常退出，为线程留下最后一个线程
        1）最低worker数可以为多少，保证至少有一个worker可以继续工作
        2）workerCountOf(c) >= min 那么就安全，可以继续执行退出的逻辑
4、异常退出：由于引起线程销毁的可能性有很多，线程池还要判断是什么引发了这次销毁，是否要改变线程池的现阶段状态，是否要根据新状态，重新分配线程。
```









### shutdown & shutdownNow 

线程关闭流程



> shutdown 只做了两件事
>
> 1）修改线程池状态为shutdown
>
> 2）标记所有的空闲工人为中断状态，告诉他们该停工了

```java
1、advanceRunState：通过cas强制设置状态为SHUTDOWN
2、获取的worker
3、中断空闲线程
	加mainLock
    	遍历所有的worker
    	分别加锁之后 给所有还在getTask中阻塞（处于take/poll状态）的worker进行中断标记
	释放mainLock
4、释放全局锁mainLock
5、tryTerminate();
```



> shutdownNow 不过是多了一个返回的方法，把workQueue里面的未处理的任务拿出来进行处理

``` java
1、advanceRunState：通过cas强制设置状态为SHUTDOWN
2、获取的worker
3、中断空闲线程
    加mainLock
        遍历所有的worker
    	分别加锁之后 给所有还在getTask中阻塞（处于take/poll状态）的worker进行中断标记
    释放mainLock
4、tasks = drainQueue();
5、释放全局锁mainLock
6、tryTerminate();
7、return tasks;
```



### tryTerminate

```java
1、进入自旋
    1）running
    2）其他线程正在TIDYING -> TERMINATED
    3）shutdown且workQueue不为空runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()

2、中断懒惰员工的方法。
    if (workerCountOf(c) != 0) { 只要线程池还有员工就执行interruptIdleWorkers

3、最后一个worker负责
        0、加锁mainLock
        1、设置状态为TIDYING
        2、设置状态为TERMINATED
        3、termination.signalAll();  通知外部线程等待线程池变为terminated状态
        0、释放mainLock
```










## 参考

https://tech.meituan.com/2020/04/02/java-pooling-pratice-in-meituan.html