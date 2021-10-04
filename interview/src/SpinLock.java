import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SpinLock {

    AtomicReference<Thread> atomicReference = new AtomicReference<>();

    public static void main(String[] args) {

        SpinLock spinLock = new SpinLock();
        new Thread(()->{
            spinLock.myLock();
            try { TimeUnit.SECONDS.sleep(5);} catch (InterruptedException e) {e.printStackTrace();}
            spinLock.myUnlock();
        },"t1").start();

        new Thread(()->{
            spinLock.myLock();
            spinLock.myUnlock();
        },"t2").start();
    }

    public void myLock() {
        System.out.println(Thread.currentThread().getName() + "尝试抢占锁……");
        while (!atomicReference.compareAndSet(null, Thread.currentThread())) {
        }
        System.out.println(Thread.currentThread().getName() + "抢占锁成功");
    }

    public void myUnlock() {
        atomicReference.compareAndSet(Thread.currentThread(), null);
        System.out.println(Thread.currentThread().getName() + "释放锁成功");
    }
}
