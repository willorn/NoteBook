package java.util.concurrent;

public class ConcurrentHashMap<K,V> extends AbstractMap<K,V>
    implements ConcurrentMap<K,V>, Serializable {
    private static final long serialVersionUID = 7249069246763182397L;

    /* ---------------- Constants -------------- */

    /**
     * The largest possible table capacity.  This value must be
     * exactly 1<<30 to stay within Java array allocation and indexing
     * bounds for power of two table sizes, and is further required
     * because the top two bits of 32bit hash fields are used for
     * control purposes.
     * 散列表数组最大限制
     */
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * The default initial table capacity.  Must be a power of 2
     * (i.e., at least 1) and at most MAXIMUM_CAPACITY.
     * 散列表默认值
     */
    private static final int DEFAULT_CAPACITY = 16;

    /**
     * The largest possible (non-power of two) array size.
     * Needed by toArray and related methods.
     */
    static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * The default concurrency level for this table. Unused but
     * defined for compatibility with previous versions of this class.
     * 并发级别，jdk1.7遗留下来的，1.8只有在初始化的时候用了一用。
     * 不代表并发级别。
     */
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    /**
     * The load factor for this table. Overrides of this value in
     * constructors affect only the initial table capacity.  The
     * actual floating point value isn't normally used -- it is
     * simpler to use expressions such as {@code n - (n >>> 2)} for
     * the associated resizing threshold.
     * 负载因子，JDK1.8中 ConcurrentHashMap 是固定值
     */
    private static final float LOAD_FACTOR = 0.75f;

    /**
     * The bin count threshold for using a tree rather than list for a
     * bin.  Bins are converted to trees when adding an element to a
     * bin with at least this many nodes. The value must be greater
     * than 2, and should be at least 8 to mesh with assumptions in
     * tree removal about conversion back to plain bins upon
     * shrinkage.
     * 树化阈值，指定桶位 链表长度达到8的话，有可能发生树化操作。
     */
    static final int TREEIFY_THRESHOLD = 8;

    /**
     * The bin count threshold for untreeifying a (split) bin during a
     * resize operation. Should be less than TREEIFY_THRESHOLD, and at
     * most 6 to mesh with shrinkage detection under removal.
     * 红黑树转化为链表的阈值
     */
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * The smallest table capacity for which bins may be treeified.
     * (Otherwise the table is resized if too many nodes in a bin.)
     * The value should be at least 4 * TREEIFY_THRESHOLD to avoid
     * conflicts between resizing and treeification thresholds.
     * 联合TREEIFY_THRESHOLD控制桶位是否树化，只有当table数组长度达到64且 某个桶位 中的链表长度达到8，才会真正树化
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    /**
     * Minimum number of rebinnings per transfer step. Ranges are
     * subdivided to allow multiple resizer threads.  This value
     * serves as a lower bound to avoid resizers encountering
     * excessive memory contention.  The value should be at least
     * DEFAULT_CAPACITY.
     * 线程迁移数据最小步长，控制线程迁移任务最小区间一个值
     */
    private static final int MIN_TRANSFER_STRIDE = 16;

    /**
     * The number of bits used for generation stamp in sizeCtl.
     * Must be at least 6 for 32bit arrays.
     */
    private static int RESIZE_STAMP_BITS = 16;

    /**
     * The maximum number of threads that can help resize.
     * Must fit in 32 - RESIZE_STAMP_BITS bits.
     */
    private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1;

    /**
     * The bit shift for recording size stamp in sizeCtl.
     */
    private static final int RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS;

    /*
     * Encodings for Node hash fields. See above for explanation.
     */
    static final int MOVED     = -1; // hash for forwarding nodes
    static final int TREEBIN   = -2; // hash for roots of trees
    static final int RESERVED  = -3; // hash for transient reservations
    static final int HASH_BITS = 0x7fffffff; // usable bits of normal node hash

    /** Number of CPUS, to place bounds on some sizings.
     */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /** For serialization compatibility.
     */
    private static final ObjectStreamField[] serialPersistentFields = {
        new ObjectStreamField("segments", Segment[].class),
        new ObjectStreamField("segmentMask", Integer.TYPE),
        new ObjectStreamField("segmentShift", Integer.TYPE)
    };

    /* ---------------- Nodes -------------- */

    /**
     * Key-value entry.  This class is never exported out as a
     * user-mutable Map.Entry (i.e., one supporting setValue; see
     * MapEntry below), but can be used for read-only traversals used
     * in bulk tasks.  Subclasses of Node with a negative hash field
     * are special, and contain null keys and values (but are never
     * exported).  Otherwise, keys and vals are never null.
     */
    static class Node<K,V> implements Map.Entry<K,V> {
        final int hash;
        final K key;
        volatile V val;
        volatile Node<K,V> next;

        Node(int hash, K key, V val, Node<K,V> next) {
            this.hash = hash;
            this.key = key;
            this.val = val;
            this.next = next;
        }

        public final K getKey()       { return key; }
        public final V getValue()     { return val; }
        public final int hashCode()   { return key.hashCode() ^ val.hashCode(); }
        public final String toString(){ return key + "=" + val; }
        public final V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        public final boolean equals(Object o) {
            Object k, v, u; Map.Entry<?,?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == (u = val) || v.equals(u)));
        }

        /**
         * Virtualized support for map.get(); overridden in subclasses.
         */
        Node<K,V> find(int h, Object k) {
            Node<K,V> e = this;
            if (k != null) {
                do {
                    K ek;
                    if (e.hash == h &&
                        ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                } while ((e = e.next) != null);
            }
            return null;
        }
    }

    /* ---------------- Static utilities -------------- */

    /**
     * Spreads (XORs) higher bits of hash to lower and also forces top
     * bit to 0. Because the table uses power-of-two masking, sets of
     * hashes that vary only in bits above the current mask will
     * always collide. (Among known examples are sets of Float keys
     * holding consecutive whole numbers in small tables.)  So we
     * apply a transform that spreads the impact of higher bits
     * downward. There is a tradeoff between speed, utility, and
     * quality of bit-spreading. Because many common sets of hashes
     * are already reasonably distributed (so don't benefit from
     * spreading), and because we use trees to handle large sets of
     * collisions in bins, we just XOR some shifted bits in the
     * cheapest possible way to reduce systematic lossage, as well as
     * to incorporate impact of the highest bits that would otherwise
     * never be used in index calculations because of table bounds.
     *
     * 1100 0011 1010 0101 0001 1100 0001 1110
     * 0000 0000 0000 0000 1100 0011 1010 0101
     * 1100 0011 1010 0101 1101 1111 1011 1011
     * ---------------------------------------
     * 1100 0011 1010 0101 1101 1111 1011 1011
     * 0111 1111 1111 1111 1111 1111 1111 1111
     * 0100 0011 1010 0101 1101 1111 1011 1011
     */
    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    /**
     * Returns a power of two table size for the given desired capacity.
     * See Hackers Delight, sec 3.2
     * 返回>=c的最小的2的次方数
     * c=28
     * n=27 => 0b 11011
     * 11011 | 01101 => 11111
     * 11111 | 00111 => 11111
     * ....
     * => 11111 + 1 =100000 = 32
     */
    private static final int tableSizeFor(int c) {
        int n = c - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /**
     * Returns x's Class if it is of the form "class C implements
     * Comparable<C>", else null.
     */
    static Class<?> comparableClassFor(Object x) {
        if (x instanceof Comparable) {
            Class<?> c; Type[] ts, as; Type t; ParameterizedType p;
            if ((c = x.getClass()) == String.class) // bypass checks
                return c;
            if ((ts = c.getGenericInterfaces()) != null) {
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                        ((p = (ParameterizedType)t).getRawType() ==
                         Comparable.class) &&
                        (as = p.getActualTypeArguments()) != null &&
                        as.length == 1 && as[0] == c) // type arg is c
                        return c;
                }
            }
        }
        return null;
    }

    /**
     * Returns k.compareTo(x) if x matches kc (k's screened comparable
     * class), else 0.
     */
    @SuppressWarnings({"rawtypes","unchecked"}) // for cast to Comparable
    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 :
                ((Comparable)k).compareTo(x));
    }

    /* ---------------- Table element access -------------- */

    /*
     * Volatile access methods are used for table elements as well as
     * elements of in-progress next table while resizing.  All uses of
     * the tab arguments must be null checked by callers.  All callers
     * also paranoically precheck that tab's length is not zero (or an
     * equivalent check), thus ensuring that any index argument taking
     * the form of a hash value anded with (length - 1) is a valid
     * index.  Note that, to be correct wrt arbitrary concurrency
     * errors by users, these checks must operate on local variables,
     * which accounts for some odd-looking inline assignments below.
     * Note that calls to setTabAt always occur within locked regions,
     * and so in principle require only release ordering, not
     * full volatile semantics, but are currently coded as volatile
     * writes to be conservative.
     */

    @SuppressWarnings("unchecked")
    static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
        return (Node<K,V>)U.getObjectVolatile(tab, ((long)i << ASHIFT) + ABASE);
    }

    static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i,
                                        Node<K,V> c, Node<K,V> v) {
        return U.compareAndSwapObject(tab, ((long)i << ASHIFT) + ABASE, c, v);
    }

    static final <K,V> void setTabAt(Node<K,V>[] tab, int i, Node<K,V> v) {
        U.putObjectVolatile(tab, ((long)i << ASHIFT) + ABASE, v);
    }

    /* ---------------- Fields -------------- */

    /**
     * The array of bins. Lazily initialized upon first insertion.
     * Size is always a power of two. Accessed directly by iterators.
     * 散列表，长度一定是2次方数
     */
    transient volatile Node<K,V>[] table;

    /**
     * The next table to use; non-null only while resizing.
     * 扩容过程中，会将扩容中的新table 赋值给nextTable 保持引用，扩容结束之后，这里会被设置为Null
     */
    private transient volatile Node<K,V>[] nextTable;

    /**
     * Base counter value, used mainly when there is no contention,
     * but also as a fallback during table initialization
     * races. Updated via CAS.
     * LongAdder 中的 baseCount 未发生竞争时 或者 当前LongAdder处于加锁状态时，增量累到到baseCount中
     */
    private transient volatile long baseCount;

    /**
     * Table initialization and resizing control.  When negative, the
     * table is being initialized or resized: -1 for initialization,
     * else -(1 + the number of active resizing threads).  Otherwise,
     * when table is null, holds the initial table size to use upon
     * creation, or 0 for default. After initialization, holds the
     * next element count value upon which to resize the table.
     * sizeCtl < 0
     * 1. -1 表示当前table正在初始化（有线程在创建table数组），当前线程需要自旋等待..
     * 2.表示当前table数组正在进行扩容 ,高16位表示：扩容的标识戳   低16位表示：（1 + nThread） 当前参与并发扩容的线程数量
     *
     * sizeCtl = 0，表示创建table数组时 使用DEFAULT_CAPACITY为大小
     *
     * sizeCtl > 0
     *
     * 1. 如果table未初始化，表示初始化大小
     * 2. 如果table已经初始化，表示下次扩容时的 触发条件（阈值）
     */
    private transient volatile int sizeCtl;

    /**
     * The next table index (plus one) to split while resizing.
     * 扩容过程中，记录当前进度。所有线程都需要从transferIndex中分配区间任务，去执行自己的任务。
     */
    private transient volatile int transferIndex;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating CounterCells.
     * LongAdder中的cellsBuzy 0表示当前LongAdder对象无锁状态，1表示当前LongAdder对象加锁状态
     */
    private transient volatile int cellsBusy;

    /**
     * Table of counter cells. When non-null, size is a power of 2.
     * LongAdder中的cells数组，当baseCount发生竞争后，会创建cells数组，
     * 线程会通过计算hash值 取到 自己的cell ，将增量累加到指定cell中
     * 总数 = sum(cells) + baseCount
     */
    private transient volatile CounterCell[] counterCells;

    // views
    private transient KeySetView<K,V> keySet;
    private transient ValuesView<K,V> values;
    private transient EntrySetView<K,V> entrySet;


    /* ---------------- Public operations -------------- */

    /**
     * Creates a new, empty map with the default initial table size (16).
     */
    public ConcurrentHashMap() {
    }

    /**
     * Creates a new, empty map with an initial table size
     * accommodating the specified number of elements without the need
     * to dynamically resize.
     *
     * @param initialCapacity The implementation performs internal
     * sizing to accommodate this many elements.
     * @throws IllegalArgumentException if the initial capacity of
     * elements is negative
     */
    public ConcurrentHashMap(int initialCapacity) {
        if (initialCapacity < 0)
            throw new IllegalArgumentException();

        int cap = ((initialCapacity >= (MAXIMUM_CAPACITY >>> 1)) ?
                   MAXIMUM_CAPACITY :
                   tableSizeFor(initialCapacity + (initialCapacity >>> 1) + 1));
        /**
         * sizeCtl > 0
         * 当目前table未初始化时，sizeCtl表示初始化容量
         */
        this.sizeCtl = cap;
    }

    /**
     * Creates a new map with the same mappings as the given map.
     *
     * @param m the map
     */
    public ConcurrentHashMap(Map<? extends K, ? extends V> m) {
        this.sizeCtl = DEFAULT_CAPACITY;
        putAll(m);
    }

    /**
     * Creates a new, empty map with an initial table size based on
     * the given number of elements ({@code initialCapacity}) and
     * initial table density ({@code loadFactor}).
     *
     * @param initialCapacity the initial capacity. The implementation
     * performs internal sizing to accommodate this many elements,
     * given the specified load factor.
     * @param loadFactor the load factor (table density) for
     * establishing the initial table size
     * @throws IllegalArgumentException if the initial capacity of
     * elements is negative or the load factor is nonpositive
     *
     * @since 1.6
     */
    public ConcurrentHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, 1);
    }

    /**
     * Creates a new, empty map with an initial table size based on
     * the given number of elements ({@code initialCapacity}), table
     * density ({@code loadFactor}), and number of concurrently
     * updating threads ({@code concurrencyLevel}).
     *
     * @param initialCapacity the initial capacity. The implementation
     * performs internal sizing to accommodate this many elements,
     * given the specified load factor.
     * @param loadFactor the load factor (table density) for
     * establishing the initial table size
     * @param concurrencyLevel the estimated number of concurrently
     * updating threads. The implementation may use this value as
     * a sizing hint.
     * @throws IllegalArgumentException if the initial capacity is
     * negative or the load factor or concurrencyLevel are
     * nonpositive
     */
    public ConcurrentHashMap(int initialCapacity,
                             float loadFactor, int concurrencyLevel) {
        if (!(loadFactor > 0.0f) || initialCapacity < 0 || concurrencyLevel <= 0)
            throw new IllegalArgumentException();

        if (initialCapacity < concurrencyLevel)   // Use at least as many bins
            initialCapacity = concurrencyLevel;   // as estimated threads

        long size = (long)(1.0 + (long)initialCapacity / loadFactor);
        int cap = (size >= (long)MAXIMUM_CAPACITY) ?
            MAXIMUM_CAPACITY : tableSizeFor((int)size);
        /**
         * sizeCtl > 0
         * 当目前table未初始化时，sizeCtl表示初始化容量
         */
        this.sizeCtl = cap;
    }

    // Original (since JDK1.2) Map methods

    /**
     * {@inheritDoc}
     */
    public int size() {
        long n = sumCount();
        return ((n < 0L) ? 0 :
                (n > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE :
                (int)n);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty() {
        return sumCount() <= 0L; // ignore transient negative values
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this map contains no mapping for the key.
     *
     * <p>More formally, if this map contains a mapping from a key
     * {@code k} to a value {@code v} such that {@code key.equals(k)},
     * then this method returns {@code v}; otherwise it returns
     * {@code null}.  (There can be at most one such mapping.)
     *
     * @throws NullPointerException if the specified key is null
     */
    public V get(Object key) {
        //tab 引用map.table
        //e 当前元素
        //p 目标节点
        //n table数组长度
        //eh 当前元素hash
        //ek 当前元素key
        Node<K,V>[] tab; Node<K,V> e, p; int n, eh; K ek;
        //扰动运算后得到 更散列的hash值
        int h = spread(key.hashCode());

        //条件一：(tab = table) != null
        //true->表示已经put过数据，并且map内部的table也已经初始化完毕
        //false->表示创建完map后，并没有put过数据，map内部的table是延迟初始化的，只有第一次写数据时会触发创建逻辑。
        //条件二：(n = tab.length) > 0 true->表示table已经初始化
        //条件三：(e = tabAt(tab, (n - 1) & h)) != null
        //true->当前key寻址的桶位 有值
        //false->当前key寻址的桶位中是null，是null直接返回null
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (e = tabAt(tab, (n - 1) & h)) != null) {
            //前置条件：当前桶位有数据

            //对比头结点hash与查询key的hash是否一致
            //条件成立：说明头结点与查询Key的hash值 完全一致
            if ((eh = e.hash) == h) {
                //完全比对 查询key 和 头结点的key
                //条件成立：说明头结点就是查询数据
                if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                    return e.val;
            }

            //条件成立：
            //1.-1  fwd 说明当前table正在扩容，且当前查询的这个桶位的数据 已经被迁移走了
            //2.-2  TreeBin节点，需要使用TreeBin 提供的find 方法查询。
            else if (eh < 0)
                return (p = e.find(h, key)) != null ? p.val : null;




            //当前桶位已经形成链表的这种情况
            while ((e = e.next) != null) {
                if (e.hash == h &&
                    ((ek = e.key) == key || (ek != null && key.equals(ek))))
                    return e.val;
            }

        }
        return null;
    }

    /**
     * Tests if the specified object is a key in this table.
     *
     * @param  key possible key
     * @return {@code true} if and only if the specified object
     *         is a key in this table, as determined by the
     *         {@code equals} method; {@code false} otherwise
     * @throws NullPointerException if the specified key is null
     */
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    /**
     * Returns {@code true} if this map maps one or more keys to the
     * specified value. Note: This method may require a full traversal
     * of the map, and is much slower than method {@code containsKey}.
     *
     * @param value value whose presence in this map is to be tested
     * @return {@code true} if this map maps one or more keys to the
     *         specified value
     * @throws NullPointerException if the specified value is null
     */
    public boolean containsValue(Object value) {
        if (value == null)
            throw new NullPointerException();
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                V v;
                if ((v = p.val) == value || (v != null && value.equals(v)))
                    return true;
            }
        }
        return false;
    }

    /**
     * Maps the specified key to the specified value in this table.
     * Neither the key nor the value can be null.
     *
     * <p>The value can be retrieved by calling the {@code get} method
     * with a key that is equal to the original key.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or
     *         {@code null} if there was no mapping for {@code key}
     * @throws NullPointerException if the specified key or value is null
     */
    public V put(K key, V value) {
        return putVal(key, value, false);
    }

    /** Implementation for put and putIfAbsent */
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        //控制k 和 v 不能为null
        if (key == null || value == null) throw new NullPointerException();

        //通过spread方法，可以让高位也能参与进寻址运算。
        int hash = spread(key.hashCode());
        //binCount表示当前k-v 封装成node后插入到指定桶位后，在桶位中的所属链表的下标位置
        //0 表示当前桶位为null，node可以直接放着
        //2 表示当前桶位已经可能是红黑树
        int binCount = 0;

        //tab 引用map对象的table
        //自旋
        for (Node<K,V>[] tab = table;;) {
            //f 表示桶位的头结点
            //n 表示散列表数组的长度
            //i 表示key通过寻址计算后，得到的桶位下标
            //fh 表示桶位头结点的hash值
            Node<K,V> f; int n, i, fh;

            //CASE1：成立，表示当前map中的table尚未初始化..
            if (tab == null || (n = tab.length) == 0)
                //最终当前线程都会获取到最新的map.table引用。
                tab = initTable();
            //CASE2：i 表示key使用路由寻址算法得到 key对应 table数组的下标位置，tabAt 获取指定桶位的头结点 f
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                //进入到CASE2代码块 前置条件 当前table数组i桶位是Null时。
                //使用CAS方式 设置 指定数组i桶位 为 new Node<K,V>(hash, key, value, null),并且期望值是null
                //cas操作成功 表示ok，直接break for循环即可
                //cas操作失败，表示在当前线程之前，有其它线程先你一步向指定i桶位设置值了。
                //当前线程只能再次自旋，去走其它逻辑。
                if (casTabAt(tab, i, null,
                             new Node<K,V>(hash, key, value, null)))
                    break;                   // no lock when adding to empty bin
            }

            //CASE3：前置条件，桶位的头结点一定不是null。
            //条件成立表示当前桶位的头结点 为 FWD结点，表示目前map正处于扩容过程中..
            else if ((fh = f.hash) == MOVED)
                //看到fwd节点后，当前节点有义务帮助当前map对象完成迁移数据的工作
                //学完扩容后再来看。
                tab = helpTransfer(tab, f);

            //CASE4：当前桶位 可能是 链表 也可能是 红黑树代理结点TreeBin
            else {
                //当插入key存在时，会将旧值赋值给oldVal，返回给put方法调用处..
                V oldVal = null;

                //使用sync 加锁“头节点”，理论上是“头结点”
                synchronized (f) {
                    //为什么又要对比一下，看看当前桶位的头节点 是否为 之前获取的头结点？
                    //为了避免其它线程将该桶位的头结点修改掉，导致当前线程从sync 加锁 就有问题了。之后所有操作都不用在做了。
                    if (tabAt(tab, i) == f) {//条件成立，说明咱们 加锁 的对象没有问题，可以进来造了！

                        //条件成立，说明当前桶位就是普通链表桶位。
                        if (fh >= 0) {
                            //1.当前插入key与链表当中所有元素的key都不一致时，当前的插入操作是追加到链表的末尾，binCount表示链表长度
                            //2.当前插入key与链表当中的某个元素的key一致时，当前插入操作可能就是替换了。binCount表示冲突位置（binCount - 1）
                            binCount = 1;

                            //迭代循环当前桶位的链表，e是每次循环处理节点。
                            for (Node<K,V> e = f;; ++binCount) {
                                //当前循环节点 key
                                K ek;
                                //条件一：e.hash == hash 成立 表示循环的当前元素的hash值与插入节点的hash值一致，需要进一步判断
                                //条件二：((ek = e.key) == key ||(ek != null && key.equals(ek)))
                                //       成立：说明循环的当前节点与插入节点的key一致，发生冲突了
                                if (e.hash == hash &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    //将当前循环的元素的 值 赋值给oldVal
                                    oldVal = e.val;

                                    if (!onlyIfAbsent)
                                        e.val = value;
                                    break;
                                }
                                //当前元素 与 插入元素的key不一致 时，会走下面程序。
                                //1.更新循环处理节点为 当前节点的下一个节点
                                //2.判断下一个节点是否为null，如果是null，说明当前节点已经是队尾了，插入数据需要追加到队尾节点的后面。

                                Node<K,V> pred = e;
                                if ((e = e.next) == null) {
                                    pred.next = new Node<K,V>(hash, key,
                                                              value, null);
                                    break;
                                }
                            }
                        }
                        //前置条件，该桶位一定不是链表
                        //条件成立，表示当前桶位是 红黑树代理结点TreeBin
                        else if (f instanceof TreeBin) {
                            //p 表示红黑树中如果与你插入节点的key 有冲突节点的话 ，则putTreeVal 方法 会返回冲突节点的引用。
                            Node<K,V> p;
                            //强制设置binCount为2，因为binCount <= 1 时有其它含义，所以这里设置为了2 回头讲 addCount。
                            binCount = 2;

                            //条件一：成立，说明当前插入节点的key与红黑树中的某个节点的key一致，冲突了
                            if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                                           value)) != null) {
                                //将冲突节点的值 赋值给 oldVal
                                oldVal = p.val;
                                if (!onlyIfAbsent)
                                    p.val = value;
                            }
                        }
                    }
                }

                //说明当前桶位不为null，可能是红黑树 也可能是链表
                if (binCount != 0) {
                    //如果binCount>=8 表示处理的桶位一定是链表
                    if (binCount >= TREEIFY_THRESHOLD)
                        //调用转化链表为红黑树的方法
                        treeifyBin(tab, i);
                    //说明当前线程插入的数据key，与原有k-v发生冲突，需要将原数据v返回给调用者。
                    if (oldVal != null)
                        return oldVal;
                    break;
                }
            }
        }

        //1.统计当前table一共有多少数据
        //2.判断是否达到扩容阈值标准，触发扩容。
        addCount(1L, binCount);

        return null;
    }

    /**
     * Copies all of the mappings from the specified map to this one.
     * These mappings replace any mappings that this map had for any of the
     * keys currently in the specified map.
     *
     * @param m mappings to be stored in this map
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        tryPresize(m.size());
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet())
            putVal(e.getKey(), e.getValue(), false);
    }

    /**
     * Removes the key (and its corresponding value) from this map.
     * This method does nothing if the key is not in the map.
     *
     * @param  key the key that needs to be removed
     * @return the previous value associated with {@code key}, or
     *         {@code null} if there was no mapping for {@code key}
     * @throws NullPointerException if the specified key is null
     */
    public V remove(Object key) {
        return replaceNode(key, null, null);
    }

    /**
     * Implementation for the four public remove/replace methods:
     * Replaces node value with v, conditional upon match of cv if
     * non-null.  If resulting value is null, delete.
     */
    final V replaceNode(Object key, V value, Object cv) {
        //计算key经过扰动运算后的hash
        int hash = spread(key.hashCode());
        //自旋
        for (Node<K,V>[] tab = table;;) {
            //f表示桶位头结点
            //n表示当前table数组长度
            //i表示hash命中桶位下标
            //fh表示桶位头结点 hash
            Node<K,V> f; int n, i, fh;

            //CASE1：
            //条件一：tab == null  true->表示当前map.table尚未初始化..  false->已经初始化
            //条件二：(n = tab.length) == 0  true->表示当前map.table尚未初始化..  false->已经初始化
            //条件三：(f = tabAt(tab, i = (n - 1) & hash)) == null true -> 表示命中桶位中为null，直接break， 会返回
            if (tab == null || (n = tab.length) == 0 ||
                (f = tabAt(tab, i = (n - 1) & hash)) == null)
                break;

            //CASE2：
            //前置条件CASE2 ~ CASE3：当前桶位不是null
            //条件成立：说明当前table正在扩容中，当前是个写操作，所以当前线程需要协助table完成扩容。
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);

            //CASE3:
            //前置条件CASE2 ~ CASE3：当前桶位不是null
            //当前桶位 可能是 "链表" 也可能 是  "红黑树" TreeBin
            else {
                //保留替换之前的数据引用
                V oldVal = null;
                //校验标记
                boolean validated = false;
                //加锁当前桶位 头结点，加锁成功之后会进入 代码块。
                synchronized (f) {
                    //判断sync加锁是否为当前桶位 头节点，防止其它线程，在当前线程加锁成功之前，修改过 桶位 的头结点。
                    //条件成立：当前桶位头结点 仍然为f，其它线程没修改过。
                    if (tabAt(tab, i) == f) {
                        //条件成立：说明桶位 为 链表 或者 单个 node
                        if (fh >= 0) {
                            validated = true;

                            //e 表示当前循环处理元素
                            //pred 表示当前循环节点的上一个节点
                            Node<K,V> e = f, pred = null;
                            for (;;) {
                                //当前节点key
                                K ek;
                                //条件一：e.hash == hash true->说明当前节点的hash与查找节点hash一致
                                //条件二：((ek = e.key) == key || (ek != null && key.equals(ek)))
                                //if 条件成立，说明key 与查询的key完全一致。
                                if (e.hash == hash &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    //当前节点的value
                                    V ev = e.val;

                                    //条件一：cv == null true->替换的值为null 那么就是一个删除操作
                                    //条件二：cv == ev || (ev != null && cv.equals(ev))  那么是一个替换操作
                                    if (cv == null || cv == ev ||
                                        (ev != null && cv.equals(ev))) {
                                        //删除 或者 替换

                                        //将当前节点的值 赋值给 oldVal 后续返回会用到
                                        oldVal = ev;

                                        //条件成立：说明当前是一个替换操作
                                        if (value != null)
                                            //直接替换
                                            e.val = value;
                                        //条件成立：说明当前节点非头结点
                                        else if (pred != null)
                                            //当前节点的上一个节点，指向当前节点的下一个节点。
                                            pred.next = e.next;

                                        else
                                            //说明当前节点即为 头结点，只需要将 桶位设置为头结点的下一个节点。
                                            setTabAt(tab, i, e.next);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        }

                        //条件成立：TreeBin节点。
                        else if (f instanceof TreeBin) {
                            validated = true;

                            //转换为实际类型 TreeBin t
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            //r 表示 红黑树 根节点
                            //p 表示 红黑树中查找到对应key 一致的node
                            TreeNode<K,V> r, p;

                            //条件一：(r = t.root) != null 理论上是成立
                            //条件二：TreeNode.findTreeNode 以当前节点为入口，向下查找key（包括本身节点）
                            //      true->说明查找到相应key 对应的node节点。会赋值给p
                            if ((r = t.root) != null &&
                                (p = r.findTreeNode(hash, key, null)) != null) {
                                //保存p.val 到pv
                                V pv = p.val;

                                //条件一：cv == null  成立：不必对value，就做替换或者删除操作
                                //条件二：cv == pv ||(pv != null && cv.equals(pv)) 成立：说明“对比值”与当前p节点的值 一致
                                if (cv == null || cv == pv ||
                                    (pv != null && cv.equals(pv))) {
                                    //替换或者删除操作


                                    oldVal = pv;

                                    //条件成立：替换操作
                                    if (value != null)
                                        p.val = value;


                                    //删除操作
                                    else if (t.removeTreeNode(p))
                                        //这里没做判断，直接搞了...很疑惑
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                //当其他线程修改过桶位 头结点时，当前线程 sync 头结点 锁错对象时，validated 为false，会进入下次for 自旋
                if (validated) {

                    if (oldVal != null) {
                        //替换的值 为null，说明当前是一次删除操作，oldVal ！=null 成立，说明删除成功，更新当前元素个数计数器。
                        if (value == null)
                            addCount(-1L, -1);
                        return oldVal;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Removes all of the mappings from this map.
     */
    public void clear() {
        long delta = 0L; // negative number of deletions
        int i = 0;
        Node<K,V>[] tab = table;
        while (tab != null && i < tab.length) {
            int fh;
            Node<K,V> f = tabAt(tab, i);
            if (f == null)
                ++i;
            else if ((fh = f.hash) == MOVED) {
                tab = helpTransfer(tab, f);
                i = 0; // restart
            }
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        Node<K,V> p = (fh >= 0 ? f :
                                       (f instanceof TreeBin) ?
                                       ((TreeBin<K,V>)f).first : null);
                        while (p != null) {
                            --delta;
                            p = p.next;
                        }
                        setTabAt(tab, i++, null);
                    }
                }
            }
        }
        if (delta != 0L)
            addCount(delta, -1);
    }

    /**
     * Returns a {@link Set} view of the keys contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa. The set supports element
     * removal, which removes the corresponding mapping from this map,
     * via the {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll}, and {@code clear}
     * operations.  It does not support the {@code add} or
     * {@code addAll} operations.
     *
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The view's {@code spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#DISTINCT}, and {@link Spliterator#NONNULL}.
     *
     * @return the set view
     */
    public KeySetView<K,V> keySet() {
        KeySetView<K,V> ks;
        return (ks = keySet) != null ? ks : (keySet = new KeySetView<K,V>(this, null));
    }

    /**
     * Returns a {@link Collection} view of the values contained in this map.
     * The collection is backed by the map, so changes to the map are
     * reflected in the collection, and vice-versa.  The collection
     * supports element removal, which removes the corresponding
     * mapping from this map, via the {@code Iterator.remove},
     * {@code Collection.remove}, {@code removeAll},
     * {@code retainAll}, and {@code clear} operations.  It does not
     * support the {@code add} or {@code addAll} operations.
     *
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The view's {@code spliterator} reports {@link Spliterator#CONCURRENT}
     * and {@link Spliterator#NONNULL}.
     *
     * @return the collection view
     */
    public Collection<V> values() {
        ValuesView<K,V> vs;
        return (vs = values) != null ? vs : (values = new ValuesView<K,V>(this));
    }

    /**
     * Returns a {@link Set} view of the mappings contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  The set supports element
     * removal, which removes the corresponding mapping from the map,
     * via the {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll}, and {@code clear}
     * operations.
     *
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The view's {@code spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#DISTINCT}, and {@link Spliterator#NONNULL}.
     *
     * @return the set view
     */
    public Set<Map.Entry<K,V>> entrySet() {
        EntrySetView<K,V> es;
        return (es = entrySet) != null ? es : (entrySet = new EntrySetView<K,V>(this));
    }

    /**
     * Returns the hash code value for this {@link Map}, i.e.,
     * the sum of, for each key-value pair in the map,
     * {@code key.hashCode() ^ value.hashCode()}.
     *
     * @return the hash code value for this map
     */
    public int hashCode() {
        int h = 0;
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; )
                h += p.key.hashCode() ^ p.val.hashCode();
        }
        return h;
    }

    /**
     * Returns a string representation of this map.  The string
     * representation consists of a list of key-value mappings (in no
     * particular order) enclosed in braces ("{@code {}}").  Adjacent
     * mappings are separated by the characters {@code ", "} (comma
     * and space).  Each key-value mapping is rendered as the key
     * followed by an equals sign ("{@code =}") followed by the
     * associated value.
     *
     * @return a string representation of this map
     */
    public String toString() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        Traverser<K,V> it = new Traverser<K,V>(t, f, 0, f);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        Node<K,V> p;
        if ((p = it.advance()) != null) {
            for (;;) {
                K k = p.key;
                V v = p.val;
                sb.append(k == this ? "(this Map)" : k);
                sb.append('=');
                sb.append(v == this ? "(this Map)" : v);
                if ((p = it.advance()) == null)
                    break;
                sb.append(',').append(' ');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * Compares the specified object with this map for equality.
     * Returns {@code true} if the given object is a map with the same
     * mappings as this map.  This operation may return misleading
     * results if either map is concurrently modified during execution
     * of this method.
     *
     * @param o object to be compared for equality with this map
     * @return {@code true} if the specified object is equal to this map
     */
    public boolean equals(Object o) {
        if (o != this) {
            if (!(o instanceof Map))
                return false;
            Map<?,?> m = (Map<?,?>) o;
            Node<K,V>[] t;
            int f = (t = table) == null ? 0 : t.length;
            Traverser<K,V> it = new Traverser<K,V>(t, f, 0, f);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                V val = p.val;
                Object v = m.get(p.key);
                if (v == null || (v != val && !v.equals(val)))
                    return false;
            }
            for (Map.Entry<?,?> e : m.entrySet()) {
                Object mk, mv, v;
                if ((mk = e.getKey()) == null ||
                    (mv = e.getValue()) == null ||
                    (v = get(mk)) == null ||
                    (mv != v && !mv.equals(v)))
                    return false;
            }
        }
        return true;
    }

    /**
     * Stripped-down version of helper class used in previous version,
     * declared for the sake of serialization compatibility
     */
    static class Segment<K,V> extends ReentrantLock implements Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        final float loadFactor;
        Segment(float lf) { this.loadFactor = lf; }
    }

    /**
     * Saves the state of the {@code ConcurrentHashMap} instance to a
     * stream (i.e., serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData
     * the key (Object) and value (Object)
     * for each key-value mapping, followed by a null pair.
     * The key-value mappings are emitted in no particular order.
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        // For serialization compatibility
        // Emulate segment calculation from previous version of this class
        int sshift = 0;
        int ssize = 1;
        while (ssize < DEFAULT_CONCURRENCY_LEVEL) {
            ++sshift;
            ssize <<= 1;
        }
        int segmentShift = 32 - sshift;
        int segmentMask = ssize - 1;
        @SuppressWarnings("unchecked")
        Segment<K,V>[] segments = (Segment<K,V>[])
            new Segment<?,?>[DEFAULT_CONCURRENCY_LEVEL];
        for (int i = 0; i < segments.length; ++i)
            segments[i] = new Segment<K,V>(LOAD_FACTOR);
        s.putFields().put("segments", segments);
        s.putFields().put("segmentShift", segmentShift);
        s.putFields().put("segmentMask", segmentMask);
        s.writeFields();

        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                s.writeObject(p.key);
                s.writeObject(p.val);
            }
        }
        s.writeObject(null);
        s.writeObject(null);
        segments = null; // throw away
    }

    /**
     * Reconstitutes the instance from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        /*
         * To improve performance in typical cases, we create nodes
         * while reading, then place in table once size is known.
         * However, we must also validate uniqueness and deal with
         * overpopulated bins while doing so, which requires
         * specialized versions of putVal mechanics.
         */
        sizeCtl = -1; // force exclusion for table construction
        s.defaultReadObject();
        long size = 0L;
        Node<K,V> p = null;
        for (;;) {
            @SuppressWarnings("unchecked")
            K k = (K) s.readObject();
            @SuppressWarnings("unchecked")
            V v = (V) s.readObject();
            if (k != null && v != null) {
                p = new Node<K,V>(spread(k.hashCode()), k, v, p);
                ++size;
            }
            else
                break;
        }
        if (size == 0L)
            sizeCtl = 0;
        else {
            int n;
            if (size >= (long)(MAXIMUM_CAPACITY >>> 1))
                n = MAXIMUM_CAPACITY;
            else {
                int sz = (int)size;
                n = tableSizeFor(sz + (sz >>> 1) + 1);
            }
            @SuppressWarnings("unchecked")
            Node<K,V>[] tab = (Node<K,V>[])new Node<?,?>[n];
            int mask = n - 1;
            long added = 0L;
            while (p != null) {
                boolean insertAtFront;
                Node<K,V> next = p.next, first;
                int h = p.hash, j = h & mask;
                if ((first = tabAt(tab, j)) == null)
                    insertAtFront = true;
                else {
                    K k = p.key;
                    if (first.hash < 0) {
                        TreeBin<K,V> t = (TreeBin<K,V>)first;
                        if (t.putTreeVal(h, k, p.val) == null)
                            ++added;
                        insertAtFront = false;
                    }
                    else {
                        int binCount = 0;
                        insertAtFront = true;
                        Node<K,V> q; K qk;
                        for (q = first; q != null; q = q.next) {
                            if (q.hash == h &&
                                ((qk = q.key) == k ||
                                 (qk != null && k.equals(qk)))) {
                                insertAtFront = false;
                                break;
                            }
                            ++binCount;
                        }
                        if (insertAtFront && binCount >= TREEIFY_THRESHOLD) {
                            insertAtFront = false;
                            ++added;
                            p.next = first;
                            TreeNode<K,V> hd = null, tl = null;
                            for (q = p; q != null; q = q.next) {
                                TreeNode<K,V> t = new TreeNode<K,V>
                                    (q.hash, q.key, q.val, null, null);
                                if ((t.prev = tl) == null)
                                    hd = t;
                                else
                                    tl.next = t;
                                tl = t;
                            }
                            setTabAt(tab, j, new TreeBin<K,V>(hd));
                        }
                    }
                }
                if (insertAtFront) {
                    ++added;
                    p.next = first;
                    setTabAt(tab, j, p);
                }
                p = next;
            }
            table = tab;
            sizeCtl = n - (n >>> 2);
            baseCount = added;
        }
    }

    // ConcurrentMap methods

    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     *         or {@code null} if there was no mapping for the key
     * @throws NullPointerException if the specified key or value is null
     */
    public V putIfAbsent(K key, V value) {
        return putVal(key, value, true);
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the specified key is null
     */
    public boolean remove(Object key, Object value) {
        if (key == null)
            throw new NullPointerException();
        return value != null && replaceNode(key, null, value) != null;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if any of the arguments are null
     */
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        return replaceNode(key, newValue, oldValue) != null;
    }

    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     *         or {@code null} if there was no mapping for the key
     * @throws NullPointerException if the specified key or value is null
     */
    public V replace(K key, V value) {
        if (key == null || value == null)
            throw new NullPointerException();
        return replaceNode(key, value, null);
    }

    // Overrides of JDK8+ Map extension method defaults

    /**
     * Returns the value to which the specified key is mapped, or the
     * given default value if this map contains no mapping for the
     * key.
     *
     * @param key the key whose associated value is to be returned
     * @param defaultValue the value to return if this map contains
     * no mapping for the given key
     * @return the mapping for the key, if present; else the default value
     * @throws NullPointerException if the specified key is null
     */
    public V getOrDefault(Object key, V defaultValue) {
        V v;
        return (v = get(key)) == null ? defaultValue : v;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                action.accept(p.key, p.val);
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        Node<K,V>[] t;
        if ((t = table) != null) {
            Traverser<K,V> it = new Traverser<K,V>(t, t.length, 0, t.length);
            for (Node<K,V> p; (p = it.advance()) != null; ) {
                V oldValue = p.val;
                for (K key = p.key;;) {
                    V newValue = function.apply(key, oldValue);
                    if (newValue == null)
                        throw new NullPointerException();
                    if (replaceNode(key, newValue, oldValue) != null ||
                        (oldValue = get(key)) == null)
                        break;
                }
            }
        }
    }

    /**
     * If the specified key is not already associated with a value,
     * attempts to compute its value using the given mapping function
     * and enters it into this map unless {@code null}.  The entire
     * method invocation is performed atomically, so the function is
     * applied at most once per key.  Some attempted update operations
     * on this map by other threads may be blocked while computation
     * is in progress, so the computation should be short and simple,
     * and must not attempt to update any other mappings of this map.
     *
     * @param key key with which the specified value is to be associated
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with
     *         the specified key, or null if the computed value is null
     * @throws NullPointerException if the specified key or mappingFunction
     *         is null
     * @throws IllegalStateException if the computation detectably
     *         attempts a recursive update to this map that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the mappingFunction does so,
     *         in which case the mapping is left unestablished
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K,V> r = new ReservationNode<K,V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K,V> node = null;
                        try {
                            if ((val = mappingFunction.apply(key)) != null)
                                node = new Node<K,V>(h, key, val, null);
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                boolean added = false;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f;; ++binCount) {
                                K ek; V ev;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = e.val;
                                    break;
                                }
                                Node<K,V> pred = e;
                                if ((e = e.next) == null) {
                                    if ((val = mappingFunction.apply(key)) != null) {
                                        added = true;
                                        pred.next = new Node<K,V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                (p = r.findTreeNode(h, key, null)) != null)
                                val = p.val;
                            else if ((val = mappingFunction.apply(key)) != null) {
                                added = true;
                                t.putTreeVal(h, key, val);
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (!added)
                        return val;
                    break;
                }
            }
        }
        if (val != null)
            addCount(1L, binCount);
        return val;
    }

    /**
     * If the value for the specified key is present, attempts to
     * compute a new mapping given the key and its current mapped
     * value.  The entire method invocation is performed atomically.
     * Some attempted update operations on this map by other threads
     * may be blocked while computation is in progress, so the
     * computation should be short and simple, and must not attempt to
     * update any other mappings of this map.
     *
     * @param key key with which a value may be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or remappingFunction
     *         is null
     * @throws IllegalStateException if the computation detectably
     *         attempts a recursive update to this map that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the remappingFunction does so,
     *         in which case the mapping is unchanged
     */
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null &&
                                (p = r.findTreeNode(h, key, null)) != null) {
                                val = remappingFunction.apply(key, p.val);
                                if (val != null)
                                    p.val = val;
                                else {
                                    delta = -1;
                                    if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    /**
     * Attempts to compute a mapping for the specified key and its
     * current mapped value (or {@code null} if there is no current
     * mapping). The entire method invocation is performed atomically.
     * Some attempted update operations on this map by other threads
     * may be blocked while computation is in progress, so the
     * computation should be short and simple, and must not attempt to
     * update any other mappings of this Map.
     *
     * @param key key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or remappingFunction
     *         is null
     * @throws IllegalStateException if the computation detectably
     *         attempts a recursive update to this map that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the remappingFunction does so,
     *         in which case the mapping is unchanged
     */
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K,V> r = new ReservationNode<K,V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K,V> node = null;
                        try {
                            if ((val = remappingFunction.apply(key, null)) != null) {
                                delta = 1;
                                node = new Node<K,V>(h, key, val, null);
                            }
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    val = remappingFunction.apply(key, null);
                                    if (val != null) {
                                        delta = 1;
                                        pred.next =
                                            new Node<K,V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 1;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r, p;
                            if ((r = t.root) != null)
                                p = r.findTreeNode(h, key, null);
                            else
                                p = null;
                            V pv = (p == null) ? null : p.val;
                            val = remappingFunction.apply(key, pv);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    /**
     * If the specified key is not already associated with a
     * (non-null) value, associates it with the given value.
     * Otherwise, replaces the value with the results of the given
     * remapping function, or removes if {@code null}. The entire
     * method invocation is performed atomically.  Some attempted
     * update operations on this map by other threads may be blocked
     * while computation is in progress, so the computation should be
     * short and simple, and must not attempt to update any other
     * mappings of this Map.
     *
     * @param key key with which the specified value is to be associated
     * @param value the value to use if absent
     * @param remappingFunction the function to recompute a value if present
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or the
     *         remappingFunction is null
     * @throws RuntimeException or Error if the remappingFunction does so,
     *         in which case the mapping is unchanged
     */
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                if (casTabAt(tab, i, null, new Node<K,V>(h, key, value, null))) {
                    delta = 1;
                    val = value;
                    break;
                }
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f, pred = null;; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                    ((ek = e.key) == key ||
                                     (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(e.val, value);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K,V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    delta = 1;
                                    val = value;
                                    pred.next =
                                        new Node<K,V>(h, key, val, null);
                                    break;
                                }
                            }
                        }
                        else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> r = t.root;
                            TreeNode<K,V> p = (r == null) ? null :
                                r.findTreeNode(h, key, null);
                            val = (p == null) ? value :
                                remappingFunction.apply(p.val, value);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            }
                            else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long)delta, binCount);
        return val;
    }

    // Hashtable legacy methods

    /**
     * Legacy method testing if some key maps into the specified value
     * in this table.  This method is identical in functionality to
     * {@link #containsValue(Object)}, and exists solely to ensure
     * full compatibility with class {@link java.util.Hashtable},
     * which supported this method prior to introduction of the
     * Java Collections framework.
     *
     * @param  value a value to search for
     * @return {@code true} if and only if some key maps to the
     *         {@code value} argument in this table as
     *         determined by the {@code equals} method;
     *         {@code false} otherwise
     * @throws NullPointerException if the specified value is null
     */
    public boolean contains(Object value) {
        return containsValue(value);
    }

    /**
     * Returns an enumeration of the keys in this table.
     *
     * @return an enumeration of the keys in this table
     * @see #keySet()
     */
    public Enumeration<K> keys() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new KeyIterator<K,V>(t, f, 0, f, this);
    }

    /**
     * Returns an enumeration of the values in this table.
     *
     * @return an enumeration of the values in this table
     * @see #values()
     */
    public Enumeration<V> elements() {
        Node<K,V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new ValueIterator<K,V>(t, f, 0, f, this);
    }

    // ConcurrentHashMap-only methods

    /**
     * Returns the number of mappings. This method should be used
     * instead of {@link #size} because a ConcurrentHashMap may
     * contain more mappings than can be represented as an int. The
     * value returned is an estimate; the actual count may differ if
     * there are concurrent insertions or removals.
     *
     * @return the number of mappings
     * @since 1.8
     */
    public long mappingCount() {
        long n = sumCount();
        return (n < 0L) ? 0L : n; // ignore transient negative values
    }

    /**
     * Creates a new {@link Set} backed by a ConcurrentHashMap
     * from the given type to {@code Boolean.TRUE}.
     *
     * @param <K> the element type of the returned set
     * @return the new set
     * @since 1.8
     */
    public static <K> KeySetView<K,Boolean> newKeySet() {
        return new KeySetView<K,Boolean>
            (new ConcurrentHashMap<K,Boolean>(), Boolean.TRUE);
    }

    /**
     * Creates a new {@link Set} backed by a ConcurrentHashMap
     * from the given type to {@code Boolean.TRUE}.
     *
     * @param initialCapacity The implementation performs internal
     * sizing to accommodate this many elements.
     * @param <K> the element type of the returned set
     * @return the new set
     * @throws IllegalArgumentException if the initial capacity of
     * elements is negative
     * @since 1.8
     */
    public static <K> KeySetView<K,Boolean> newKeySet(int initialCapacity) {
        return new KeySetView<K,Boolean>
            (new ConcurrentHashMap<K,Boolean>(initialCapacity), Boolean.TRUE);
    }

    /**
     * Returns a {@link Set} view of the keys in this map, using the
     * given common mapped value for any additions (i.e., {@link
     * Collection#add} and {@link Collection#addAll(Collection)}).
     * This is of course only appropriate if it is acceptable to use
     * the same value for all additions from this view.
     *
     * @param mappedValue the mapped value to use for any additions
     * @return the set view
     * @throws NullPointerException if the mappedValue is null
     */
    public KeySetView<K,V> keySet(V mappedValue) {
        if (mappedValue == null)
            throw new NullPointerException();
        return new KeySetView<K,V>(this, mappedValue);
    }

    /* ---------------- Special Nodes -------------- */

    /**
     * A node inserted at head of bins during transfer operations.
     */
    static final class ForwardingNode<K,V> extends Node<K,V> {
        final Node<K,V>[] nextTable;
        ForwardingNode(Node<K,V>[] tab) {
            super(MOVED, null, null, null);
            this.nextTable = tab;
        }

        Node<K,V> find(int h, Object k) {
            // loop to avoid arbitrarily deep recursion on forwarding nodes
            //tab 一定不为空
            Node<K,V>[] tab = nextTable;
            outer: for (;;) {
                //n 表示为扩容而创建的 新表的长度
                //e 表示在扩容而创建新表使用 寻址算法 得到的 桶位头结点
                Node<K,V> e; int n;

                //条件一：永远不成立
                //条件二：永远不成立
                //条件三：永远不成立
                //条件四：在新扩容表中 重新定位 hash 对应的头结点
                //true -> 1.在oldTable中 对应的桶位在迁移之前就是null
                //        2.扩容完成后，有其它写线程，将此桶位设置为了null
                if (k == null || tab == null || (n = tab.length) == 0 ||
                    (e = tabAt(tab, (n - 1) & h)) == null)
                    return null;

                //前置条件：扩容后的表 对应hash的桶位一定不是null，e为此桶位的头结点
                //e可能为哪些node类型？
                //1.node 类型
                //2.TreeBin 类型
                //3.FWD 类型

                for (;;) {
                    //eh 新扩容后表指定桶位的当前节点的hash
                    //ek 新扩容后表指定桶位的当前节点的key
                    int eh; K ek;
                    //条件成立：说明新扩容 后的表，当前命中桶位中的数据，即为 查询想要数据。
                    if ((eh = e.hash) == h &&
                        ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;

                    //eh<0
                    //1.TreeBin 类型    2.FWD类型（新扩容的表，在并发很大的情况下，可能在此方法 再次拿到FWD类型..）
                    if (eh < 0) {
                        if (e instanceof ForwardingNode) {
                            tab = ((ForwardingNode<K,V>)e).nextTable;
                            continue outer;
                        }
                        else
                            //说明此桶位 为 TreeBin 节点，使用TreeBin.find 查找红黑树中相应节点。
                            return e.find(h, k);
                    }

                    //前置条件：当前桶位头结点 并没有命中查询，说明此桶位是 链表
                    //1.将当前元素 指向链表的下一个元素
                    //2.判断当前元素的下一个位置 是否为空
                    //   true->说明迭代到链表末尾，未找到对应的数据，返回Null
                    if ((e = e.next) == null)
                        return null;
                }
            }
        }
    }

    /**
     * A place-holder node used in computeIfAbsent and compute
     */
    static final class ReservationNode<K,V> extends Node<K,V> {
        ReservationNode() {
            super(RESERVED, null, null, null);
        }

        Node<K,V> find(int h, Object k) {
            return null;
        }
    }

    /* ---------------- Table Initialization and Resizing -------------- */

    /**
     * Returns the stamp bits for resizing a table of size n.
     * Must be negative when shifted left by RESIZE_STAMP_SHIFT.
     * 16 -> 32
     * numberOfLeadingZeros(16) => 1 0000 =>27 =>0000 0000 0001 1011
     * |
     * (1 << (RESIZE_STAMP_BITS - 1)) => 1000 0000 0000 0000 => 32768
     * ---------------------------------------------------------------
     * 0000 0000 0001 1011
     * 1000 0000 0000 0000
     * 1000 0000 0001 1011
     */
    static final int resizeStamp(int n) {
        return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
    }

    /**
     * Initializes table, using the size recorded in sizeCtl.
     *      * sizeCtl < 0
     *      * 1. -1 表示当前table正在初始化（有线程在创建table数组），当前线程需要自旋等待..
     *      * 2.表示当前table数组正在进行扩容 ,高16位表示：扩容的标识戳   低16位表示：（1 + nThread） 当前参与并发扩容的线程数量
     *      *
     *      * sizeCtl = 0，表示创建table数组时 使用DEFAULT_CAPACITY为大小
     *      *
     *      * sizeCtl > 0
     *      *
     *      * 1. 如果table未初始化，表示初始化大小
     *      * 2. 如果table已经初始化，表示下次扩容时的 触发条件（阈值）
     */
    private final Node<K,V>[] initTable() {
        //tab 引用map.table
        //sc sizeCtl的临时值
        Node<K,V>[] tab; int sc;
        //自旋 条件：map.table 尚未初始化
        while ((tab = table) == null || tab.length == 0) {

            if ((sc = sizeCtl) < 0)
                //大概率就是-1，表示其它线程正在进行创建table的过程，当前线程没有竞争到初始化table的锁。
                Thread.yield(); // lost initialization race; just spin

            //1.sizeCtl = 0，表示创建table数组时 使用DEFAULT_CAPACITY为大小
            //2.如果table未初始化，表示初始化大小
            //3.如果table已经初始化，表示下次扩容时的 触发条件（阈值）
            else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                try {
                    //这里为什么又要判断呢？ 防止其它线程已经初始化完毕了，然后当前线程再次初始化..导致丢失数据。
                    //条件成立，说明其它线程都没有进入过这个if块，当前线程就是具备初始化table权利了。
                    if ((tab = table) == null || tab.length == 0) {

                        //sc大于0 创建table时 使用 sc为指定大小，否则使用 16 默认值.
                        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;

                        @SuppressWarnings("unchecked")
                        Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                        //最终赋值给 map.table
                        table = tab = nt;
                        //n >>> 2  => 等于 1/4 n     n - (1/4)n = 3/4 n => 0.75 * n
                        //sc 0.75 n 表示下一次扩容时的触发条件。
                        sc = n - (n >>> 2);
                    }
                } finally {
                    //1.如果当前线程是第一次创建map.table的线程话，sc表示的是 下一次扩容的阈值
                    //2.表示当前线程 并不是第一次创建map.table的线程，当前线程进入到else if 块 时，将
                    //sizeCtl 设置为了-1 ，那么这时需要将其修改为 进入时的值。
                    sizeCtl = sc;
                }
                break;
            }
        }
        return tab;
    }

    /**
     * Adds to count, and if table is too small and not already
     * resizing, initiates transfer. If already resizing, helps
     * perform transfer if work is available.  Rechecks occupancy
     * after a transfer to see if another resize is already needed
     * because resizings are lagging additions.
     *
     * @param x the count to add
     * @param check if <0, don't check resize, if <= 1 only check if uncontended
     */
    private final void addCount(long x, int check) {
        //as 表示 LongAdder.cells
        //b 表示LongAdder.base
        //s 表示当前map.table中元素的数量
        CounterCell[] as; long b, s;
        //条件一：true->表示cells已经初始化了，当前线程应该去使用hash寻址找到合适的cell 去累加数据
        //       false->表示当前线程应该将数据累加到 base
        //条件二：false->表示写base成功，数据累加到base中了，当前竞争不激烈，不需要创建cells
        //       true->表示写base失败，与其他线程在base上发生了竞争，当前线程应该去尝试创建cells。
        if ((as = counterCells) != null ||
            !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
            //有几种情况进入到if块中？
            //1.true->表示cells已经初始化了，当前线程应该去使用hash寻址找到合适的cell 去累加数据
            //2.true->表示写base失败，与其他线程在base上发生了竞争，当前线程应该去尝试创建cells。

            //a 表示当前线程hash寻址命中的cell
            CounterCell a;
            //v 表示当前线程写cell时的期望值
            long v;
            //m 表示当前cells数组的长度
            int m;
            //true -> 未竞争  false->发生竞争
            boolean uncontended = true;


            //条件一：as == null || (m = as.length - 1) < 0
            //true-> 表示当前线程是通过 写base竞争失败 然后进入的if块，就需要调用fullAddCount方法去扩容 或者 重试.. LongAdder.longAccumulate
            //条件二：a = as[ThreadLocalRandom.getProbe() & m]) == null   前置条件：cells已经初始化了
            //true->表示当前线程命中的cell表格是个空，需要当前线程进入fullAddCount方法去初始化 cell，放入当前位置.
            //条件三：!(uncontended = U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x)
            //      false->取反得到false，表示当前线程使用cas方式更新当前命中的cell成功
            //      true->取反得到true,表示当前线程使用cas方式更新当前命中的cell失败，需要进入fullAddCount进行重试 或者 扩容 cells。
            if (as == null || (m = as.length - 1) < 0 ||
                (a = as[ThreadLocalRandom.getProbe() & m]) == null ||
                !(uncontended = U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))
            ) {
                fullAddCount(x, uncontended);
                //考虑到fullAddCount里面的事情比较累，就让当前线程 不参与到 扩容相关的逻辑了，直接返回到调用点。
                return;
            }

            if (check <= 1)
                return;

            //获取当前散列表元素个数，这是一个期望值
            s = sumCount();
        }

        //表示一定是一个put操作调用的addCount
        if (check >= 0) {
            //tab 表示map.table
            //nt 表示map.nextTable
            //n 表示map.table数组的长度
            //sc 表示sizeCtl的临时值
            Node<K,V>[] tab, nt; int n, sc;


            /**
             * sizeCtl < 0
             * 1. -1 表示当前table正在初始化（有线程在创建table数组），当前线程需要自旋等待..
             * 2.表示当前table数组正在进行扩容 ,高16位表示：扩容的标识戳   低16位表示：（1 + nThread） 当前参与并发扩容的线程数量
             *
             * sizeCtl = 0，表示创建table数组时 使用DEFAULT_CAPACITY为大小
             *
             * sizeCtl > 0
             *
             * 1. 如果table未初始化，表示初始化大小
             * 2. 如果table已经初始化，表示下次扩容时的 触发条件（阈值）
             */

            //自旋
            //条件一：s >= (long)(sc = sizeCtl)
            //       true-> 1.当前sizeCtl为一个负数 表示正在扩容中..
            //              2.当前sizeCtl是一个正数，表示扩容阈值
            //       false-> 表示当前table尚未达到扩容条件
            //条件二：(tab = table) != null
            //       恒成立 true
            //条件三：(n = tab.length) < MAXIMUM_CAPACITY
            //       true->当前table长度小于最大值限制，则可以进行扩容。
            while (s >= (long)(sc = sizeCtl) && (tab = table) != null &&
                   (n = tab.length) < MAXIMUM_CAPACITY) {

                //扩容批次唯一标识戳
                //16 -> 32 扩容 标识为：1000 0000 0001 1011
                int rs = resizeStamp(n);

                //条件成立：表示当前table正在扩容
                //         当前线程理论上应该协助table完成扩容
                if (sc < 0) {
                    //条件一：(sc >>> RESIZE_STAMP_SHIFT) != rs
                    //      true->说明当前线程获取到的扩容唯一标识戳 非 本批次扩容
                    //      false->说明当前线程获取到的扩容唯一标识戳 是 本批次扩容
                    //条件二： JDK1.8 中有bug jira已经提出来了 其实想表达的是 =  sc == (rs << 16 ) + 1
                    //        true-> 表示扩容完毕，当前线程不需要再参与进来了
                    //        false->扩容还在进行中，当前线程可以参与
                    //条件三：JDK1.8 中有bug jira已经提出来了 其实想表达的是 = sc == (rs<<16) + MAX_RESIZERS
                    //        true-> 表示当前参与并发扩容的线程达到了最大值 65535 - 1
                    //        false->表示当前线程可以参与进来
                    //条件四：(nt = nextTable) == null
                    //        true->表示本次扩容结束
                    //        false->扩容正在进行中
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                        sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                        transferIndex <= 0)
                        break;

                    //前置条件：当前table正在执行扩容中.. 当前线程有机会参与进扩容。
                    //条件成立：说明当前线程成功参与到扩容任务中，并且将sc低16位值加1，表示多了一个线程参与工作
                    //条件失败：1.当前有很多线程都在此处尝试修改sizeCtl，有其它一个线程修改成功了，导致你的sc期望值与内存中的值不一致 修改失败
                    //        2.transfer 任务内部的线程也修改了sizeCtl。
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        //协助扩容线程，持有nextTable参数
                        transfer(tab, nt);
                }
                //1000 0000 0001 1011 0000 0000 0000 0000 +2 => 1000 0000 0001 1011 0000 0000 0000 0010
                //条件成立，说明当前线程是触发扩容的第一个线程，在transfer方法需要做一些扩容准备工作
                else if (U.compareAndSwapInt(this, SIZECTL, sc,
                                             (rs << RESIZE_STAMP_SHIFT) + 2))
                    //触发扩容条件的线程 不持有nextTable
                    transfer(tab, null);
                s = sumCount();
            }
        }
    }

    /**
     * Helps transfer if a resize is in progress.
     */
    final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
        //nextTab 引用的是 fwd.nextTable == map.nextTable 理论上是这样。
        //sc 保存map.sizeCtl
        Node<K,V>[] nextTab; int sc;

        //条件一：tab != null 恒成立 true
        //条件二：(f instanceof ForwardingNode) 恒成立 true
        //条件三：((ForwardingNode<K,V>)f).nextTable) != null 恒成立 true
        if (tab != null && (f instanceof ForwardingNode) &&
            (nextTab = ((ForwardingNode<K,V>)f).nextTable) != null) {

            //拿当前标的长度 获取 扩容标识戳   假设 16 -> 32 扩容：1000 0000 0001 1011
            int rs = resizeStamp(tab.length);

            //条件一：nextTab == nextTable
            //成立：表示当前扩容正在进行中
            //不成立：1.nextTable被设置为Null 了，扩容完毕后，会被设为Null
            //       2.再次出发扩容了...咱们拿到的nextTab 也已经过期了...
            //条件二：table == tab
            //成立：说明 扩容正在进行中，还未完成
            //不成立：说明扩容已经结束了，扩容结束之后，最后退出的线程 会设置 nextTable 为 table

            //条件三：(sc = sizeCtl) < 0
            //成立：说明扩容正在进行中
            //不成立：说明sizeCtl当前是一个大于0的数，此时代表下次扩容的阈值，当前扩容已经结束。
            while (nextTab == nextTable && table == tab &&
                   (sc = sizeCtl) < 0) {


                //条件一：(sc >>> RESIZE_STAMP_SHIFT) != rs
                //      true->说明当前线程获取到的扩容唯一标识戳 非 本批次扩容
                //      false->说明当前线程获取到的扩容唯一标识戳 是 本批次扩容
                //条件二： JDK1.8 中有bug jira已经提出来了 其实想表达的是 =  sc == (rs << 16 ) + 1
                //        true-> 表示扩容完毕，当前线程不需要再参与进来了
                //        false->扩容还在进行中，当前线程可以参与
                //条件三：JDK1.8 中有bug jira已经提出来了 其实想表达的是 = sc == (rs<<16) + MAX_RESIZERS
                //        true-> 表示当前参与并发扩容的线程达到了最大值 65535 - 1
                //        false->表示当前线程可以参与进来
                //条件四：transferIndex <= 0
                //      true->说明map对象全局范围内的任务已经分配完了，当前线程进去也没活干..
                //      false->还有任务可以分配。
                if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                    sc == rs + MAX_RESIZERS || transferIndex <= 0)
                    break;


                if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) {
                    transfer(tab, nextTab);
                    break;
                }
            }
            return nextTab;
        }
        return table;
    }

    /**
     * Tries to presize table to accommodate the given number of elements.
     *
     * @param size number of elements (doesn't need to be perfectly accurate)
     */
    private final void tryPresize(int size) {
        int c = (size >= (MAXIMUM_CAPACITY >>> 1)) ? MAXIMUM_CAPACITY :
            tableSizeFor(size + (size >>> 1) + 1);
        int sc;
        while ((sc = sizeCtl) >= 0) {
            Node<K,V>[] tab = table; int n;
            if (tab == null || (n = tab.length) == 0) {
                n = (sc > c) ? sc : c;
                if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                    try {
                        if (table == tab) {
                            @SuppressWarnings("unchecked")
                            Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                            table = nt;
                            sc = n - (n >>> 2);
                        }
                    } finally {
                        sizeCtl = sc;
                    }
                }
            }
            else if (c <= sc || n >= MAXIMUM_CAPACITY)
                break;
            else if (tab == table) {
                int rs = resizeStamp(n);
                if (sc < 0) {
                    Node<K,V>[] nt;
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                        sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                        transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                }
                else if (U.compareAndSwapInt(this, SIZECTL, sc,
                                             (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
            }
        }
    }

    /**
     * Moves and/or copies the nodes in each bin to new table. See
     * above for explanation.
     */
    private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
        //n 表示扩容之前table数组的长度
        //stride 表示分配给线程任务的步长
        int n = tab.length, stride;
        //方便讲解源码  stride 固定为 16
        if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
            stride = MIN_TRANSFER_STRIDE; // subdivide range


        //条件成立：表示当前线程为触发本次扩容的线程，需要做一些扩容准备工作
        //条件不成立：表示当前线程是协助扩容的线程..
        if (nextTab == null) {            // initiating
            try {
                //创建了一个比扩容之前大一倍的table
                @SuppressWarnings("unchecked")
                Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n << 1];
                nextTab = nt;
            } catch (Throwable ex) {      // try to cope with OOME
                sizeCtl = Integer.MAX_VALUE;
                return;
            }
            //赋值给对象属性 nextTable ，方便协助扩容线程 拿到新表
            nextTable = nextTab;
            //记录迁移数据整体位置的一个标记。index计数是从1开始计算的。
            transferIndex = n;
        }

        //表示新数组的长度
        int nextn = nextTab.length;
        //fwd 节点，当某个桶位数据处理完毕后，将此桶位设置为fwd节点，其它写线程 或读线程看到后，会有不同逻辑。
        ForwardingNode<K,V> fwd = new ForwardingNode<K,V>(nextTab);
        //推进标记
        boolean advance = true;
        //完成标记
        boolean finishing = false; // to ensure sweep before committing nextTab

        //i 表示分配给当前线程任务，执行到的桶位
        //bound 表示分配给当前线程任务的下界限制
        int i = 0, bound = 0;
        //自旋
        for (;;) {
            //f 桶位的头结点
            //fh 头结点的hash
            Node<K,V> f; int fh;


            /**
             * 1.给当前线程分配任务区间
             * 2.维护当前线程任务进度（i 表示当前处理的桶位）
             * 3.维护map对象全局范围内的进度
             */
            while (advance) {
                //分配任务的开始下标
                //分配任务的结束下标
                int nextIndex, nextBound;

                //CASE1:
                //条件一：--i >= bound
                //成立：表示当前线程的任务尚未完成，还有相应的区间的桶位要处理，--i 就让当前线程处理下一个 桶位.
                //不成立：表示当前线程任务已完成 或 者未分配
                if (--i >= bound || finishing)
                    advance = false;
                //CASE2:
                //前置条件：当前线程任务已完成 或 者未分配
                //条件成立：表示对象全局范围内的桶位都分配完毕了，没有区间可分配了，设置当前线程的i变量为-1 跳出循环后，执行退出迁移任务相关的程序
                //条件不成立：表示对象全局范围内的桶位尚未分配完毕，还有区间可分配
                else if ((nextIndex = transferIndex) <= 0) {
                    i = -1;
                    advance = false;
                }
                //CASE3:
                //前置条件：1、当前线程需要分配任务区间  2.全局范围内还有桶位尚未迁移
                //条件成立：说明给当前线程分配任务成功
                //条件失败：说明分配给当前线程失败，应该是和其它线程发生了竞争吧
                else if (U.compareAndSwapInt
                         (this, TRANSFERINDEX, nextIndex,
                          nextBound = (nextIndex > stride ?
                                       nextIndex - stride : 0))) {

                    bound = nextBound;
                    i = nextIndex - 1;
                    advance = false;
                }
            }

            //CASE1：
            //条件一：i < 0
            //成立：表示当前线程未分配到任务
            if (i < 0 || i >= n || i + n >= nextn) {
                //保存sizeCtl 的变量
                int sc;
                if (finishing) {
                    nextTable = null;
                    table = nextTab;
                    sizeCtl = (n << 1) - (n >>> 1);
                    return;
                }

                //条件成立：说明设置sizeCtl 低16位  -1 成功，当前线程可以正常退出
                if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                    //1000 0000 0001 1011 0000 0000 0000 0000
                    //条件成立：说明当前线程不是最后一个退出transfer任务的线程
                    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                        //正常退出
                        return;

                    finishing = advance = true;
                    i = n; // recheck before commit
                }
            }
            //前置条件：【CASE2~CASE4】 当前线程任务尚未处理完，正在进行中

            //CASE2:
            //条件成立：说明当前桶位未存放数据，只需要将此处设置为fwd节点即可。
            else if ((f = tabAt(tab, i)) == null)
                advance = casTabAt(tab, i, null, fwd);
            //CASE3:
            //条件成立：说明当前桶位已经迁移过了，当前线程不用再处理了，直接再次更新当前线程任务索引，再次处理下一个桶位 或者 其它操作
            else if ((fh = f.hash) == MOVED)
                advance = true; // already processed
            //CASE4:
            //前置条件：当前桶位有数据，而且node节点 不是 fwd节点，说明这些数据需要迁移。
            else {
                //sync 加锁当前桶位的头结点
                synchronized (f) {
                    //防止在你加锁头对象之前，当前桶位的头对象被其它写线程修改过，导致你目前加锁对象错误...
                    if (tabAt(tab, i) == f) {
                        //ln 表示低位链表引用
                        //hn 表示高位链表引用
                        Node<K,V> ln, hn;

                        //条件成立：表示当前桶位是链表桶位
                        if (fh >= 0) {


                            //lastRun
                            //可以获取出 当前链表 末尾连续高位不变的 node
                            int runBit = fh & n;
                            Node<K,V> lastRun = f;
                            for (Node<K,V> p = f.next; p != null; p = p.next) {
                                int b = p.hash & n;
                                if (b != runBit) {
                                    runBit = b;
                                    lastRun = p;
                                }
                            }

                            //条件成立：说明lastRun引用的链表为 低位链表，那么就让 ln 指向 低位链表
                            if (runBit == 0) {
                                ln = lastRun;
                                hn = null;
                            }
                            //否则，说明lastRun引用的链表为 高位链表，就让 hn 指向 高位链表
                            else {
                                hn = lastRun;
                                ln = null;
                            }



                            for (Node<K,V> p = f; p != lastRun; p = p.next) {
                                int ph = p.hash; K pk = p.key; V pv = p.val;
                                if ((ph & n) == 0)
                                    ln = new Node<K,V>(ph, pk, pv, ln);
                                else
                                    hn = new Node<K,V>(ph, pk, pv, hn);
                            }



                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                        //条件成立：表示当前桶位是 红黑树 代理结点TreeBin
                        else if (f instanceof TreeBin) {
                            //转换头结点为 treeBin引用 t
                            TreeBin<K,V> t = (TreeBin<K,V>)f;

                            //低位双向链表 lo 指向低位链表的头  loTail 指向低位链表的尾巴
                            TreeNode<K,V> lo = null, loTail = null;
                            //高位双向链表 lo 指向高位链表的头  loTail 指向高位链表的尾巴
                            TreeNode<K,V> hi = null, hiTail = null;


                            //lc 表示低位链表元素数量
                            //hc 表示高位链表元素数量
                            int lc = 0, hc = 0;

                            //迭代TreeBin中的双向链表，从头结点 至 尾节点
                            for (Node<K,V> e = t.first; e != null; e = e.next) {
                                // h 表示循环处理当前元素的 hash
                                int h = e.hash;
                                //使用当前节点 构建出来的 新的 TreeNode
                                TreeNode<K,V> p = new TreeNode<K,V>
                                    (h, e.key, e.val, null, null);

                                //条件成立：表示当前循环节点 属于低位链 节点
                                if ((h & n) == 0) {
                                    //条件成立：说明当前低位链表 还没有数据
                                    if ((p.prev = loTail) == null)
                                        lo = p;
                                    //说明 低位链表已经有数据了，此时当前元素 追加到 低位链表的末尾就行了
                                    else
                                        loTail.next = p;
                                    //将低位链表尾指针指向 p 节点
                                    loTail = p;
                                    ++lc;
                                }
                                //当前节点 属于 高位链 节点
                                else {
                                    if ((p.prev = hiTail) == null)
                                        hi = p;
                                    else
                                        hiTail.next = p;
                                    hiTail = p;
                                    ++hc;
                                }
                            }



                            ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                                (hc != 0) ? new TreeBin<K,V>(lo) : t;
                            hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                                (lc != 0) ? new TreeBin<K,V>(hi) : t;
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                    }
                }
            }
        }
    }

    /* ---------------- Counter support -------------- */

    /**
     * A padded cell for distributing counts.  Adapted from LongAdder
     * and Striped64.  See their internal docs for explanation.
     */
    @sun.misc.Contended static final class CounterCell {
        volatile long value;
        CounterCell(long x) { value = x; }
    }

    final long sumCount() {
        CounterCell[] as = counterCells; CounterCell a;
        long sum = baseCount;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    // See LongAdder version for explanation
    private final void fullAddCount(long x, boolean wasUncontended) {
        int h;
        if ((h = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();      // force initialization
            h = ThreadLocalRandom.getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            CounterCell[] as; CounterCell a; int n; long v;
            if ((as = counterCells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {            // Try to attach new Cell
                        CounterCell r = new CounterCell(x); // Optimistic create
                        if (cellsBusy == 0 &&
                            U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                            boolean created = false;
                            try {               // Recheck under lock
                                CounterCell[] rs; int m, j;
                                if ((rs = counterCells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))
                    break;
                else if (counterCells != as || n >= NCPU)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 &&
                         U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                    try {
                        if (counterCells == as) {// Expand table unless stale
                            CounterCell[] rs = new CounterCell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            counterCells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = ThreadLocalRandom.advanceProbe(h);
            }
            else if (cellsBusy == 0 && counterCells == as &&
                     U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                boolean init = false;
                try {                           // Initialize table
                    if (counterCells == as) {
                        CounterCell[] rs = new CounterCell[2];
                        rs[h & 1] = new CounterCell(x);
                        counterCells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (U.compareAndSwapLong(this, BASECOUNT, v = baseCount, v + x))
                break;                          // Fall back on using base
        }
    }

    /* ---------------- Conversion from/to TreeBins -------------- */

    /**
     * Replaces all linked nodes in bin at given index unless table is
     * too small, in which case resizes instead.
     */
    private final void treeifyBin(Node<K,V>[] tab, int index) {
        Node<K,V> b; int n, sc;
        if (tab != null) {
            //条件成立：说明当前table数组长度 未达到 64，此时不进行树化操作，进行扩容操作。
            if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
                tryPresize(n << 1);

            //条件成立：说明当前桶位 有数据，且是普通node数据。
            else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {

                synchronized (b) {
                    //条件成立：表示加锁没问题。
                    if (tabAt(tab, index) == b) {

                        TreeNode<K,V> hd = null, tl = null;
                        for (Node<K,V> e = b; e != null; e = e.next) {
                            TreeNode<K,V> p =
                                new TreeNode<K,V>(e.hash, e.key, e.val,
                                                  null, null);
                            if ((p.prev = tl) == null)
                                hd = p;
                            else
                                tl.next = p;
                            tl = p;
                        }


                        setTabAt(tab, index, new TreeBin<K,V>(hd));
                    }
                }
            }
        }
    }

    /**
     * Returns a list on non-TreeNodes replacing those in given list.
     */
    static <K,V> Node<K,V> untreeify(Node<K,V> b) {
        Node<K,V> hd = null, tl = null;
        for (Node<K,V> q = b; q != null; q = q.next) {
            Node<K,V> p = new Node<K,V>(q.hash, q.key, q.val, null);
            if (tl == null)
                hd = p;
            else
                tl.next = p;
            tl = p;
        }
        return hd;
    }

    /* ---------------- TreeNodes -------------- */

    /**
     * Nodes for use in TreeBins
     */
    static final class TreeNode<K,V> extends Node<K,V> {
        TreeNode<K,V> parent;  // red-black tree links
        TreeNode<K,V> left;
        TreeNode<K,V> right;
        TreeNode<K,V> prev;    // needed to unlink next upon deletion
        boolean red;

        TreeNode(int hash, K key, V val, Node<K,V> next,
                 TreeNode<K,V> parent) {
            super(hash, key, val, next);
            this.parent = parent;
        }

        Node<K,V> find(int h, Object k) {
            return findTreeNode(h, k, null);
        }

        /**
         * Returns the TreeNode (or null if not found) for the given key
         * starting at given root.
         */
        final TreeNode<K,V> findTreeNode(int h, Object k, Class<?> kc) {
            if (k != null) {
                TreeNode<K,V> p = this;
                do  {
                    int ph, dir; K pk; TreeNode<K,V> q;
                    TreeNode<K,V> pl = p.left, pr = p.right;
                    if ((ph = p.hash) > h)
                        p = pl;
                    else if (ph < h)
                        p = pr;
                    else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                        return p;
                    else if (pl == null)
                        p = pr;
                    else if (pr == null)
                        p = pl;
                    else if ((kc != null ||
                              (kc = comparableClassFor(k)) != null) &&
                             (dir = compareComparables(kc, k, pk)) != 0)
                        p = (dir < 0) ? pl : pr;
                    else if ((q = pr.findTreeNode(h, k, kc)) != null)
                        return q;
                    else
                        p = pl;
                } while (p != null);
            }
            return null;
        }
    }

    /* ---------------- TreeBins -------------- */

    /**
     * TreeNodes used at the heads of bins. TreeBins do not hold user
     * keys or values, but instead point to list of TreeNodes and
     * their root. They also maintain a parasitic read-write lock
     * forcing writers (who hold bin lock) to wait for readers (who do
     * not) to complete before tree restructuring operations.
     */
    static final class TreeBin<K,V> extends Node<K,V> {
        //红黑树 根节点 小刘讲师录制的红黑树教程：av83540396
        TreeNode<K,V> root;
        //链表的头节点
        volatile TreeNode<K,V> first;
        //等待者线程（当前lockState是读锁状态）
        volatile Thread waiter;
        /**
         * 1.写锁状态 写是独占状态，以散列表来看，真正进入到TreeBin中的写线程 同一时刻 只有一个线程。 1
         * 2.读锁状态 读锁是共享，同一时刻可以有多个线程 同时进入到 TreeBin对象中获取数据。 每一个线程 都会给 lockStat + 4
         * 3.等待者状态（写线程在等待），当TreeBin中有读线程目前正在读取数据时，写线程无法修改数据，那么就将lockState的最低2位 设置为 0b 10
         */
        volatile int lockState;

        // values for lockState
        static final int WRITER = 1; // set while holding write lock
        static final int WAITER = 2; // set when waiting for write lock
        static final int READER = 4; // increment value for setting read lock

        /**
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                (d = a.getClass().getName().
                 compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                     -1 : 1);
            return d;
        }

        /**
         * Creates bin with initial set of nodes headed by b.
         */
        TreeBin(TreeNode<K,V> b) {
            //设置节点hash为-2 表示此节点是TreeBin节点
            super(TREEBIN, null, null, null);
            //使用first 引用 treeNode链表
            this.first = b;
            //r 红黑树的根节点引用
            TreeNode<K,V> r = null;

            //x表示遍历的当前节点
            for (TreeNode<K,V> x = b, next; x != null; x = next) {
                next = (TreeNode<K,V>)x.next;
                //强制设置当前插入节点的左右子树为null
                x.left = x.right = null;
                //条件成立：说明当前红黑树 是一个空树，那么设置插入元素 为根节点
                if (r == null) {
                    //根节点的父节点 一定为 null
                    x.parent = null;
                    //颜色改为黑色
                    x.red = false;
                    //让r引用x所指向的对象。
                    r = x;
                }

                else {
                    //非第一次循环，都会来带else分支，此时红黑树已经有数据了

                    //k 表示 插入节点的key
                    K k = x.key;
                    //h 表示 插入节点的hash
                    int h = x.hash;
                    //kc 表示 插入节点key的class类型
                    Class<?> kc = null;
                    //p 表示 为查找插入节点的父节点的一个临时节点
                    TreeNode<K,V> p = r;

                    for (;;) {
                        //dir (-1, 1)
                        //-1 表示插入节点的hash值大于 当前p节点的hash
                        //1 表示插入节点的hash值 小于 当前p节点的hash
                        //ph p表示 为查找插入节点的父节点的一个临时节点的hash
                        int dir, ph;
                        //临时节点 key
                        K pk = p.key;

                        //插入节点的hash值 小于 当前节点
                        if ((ph = p.hash) > h)
                            //插入节点可能需要插入到当前节点的左子节点 或者 继续在左子树上查找
                            dir = -1;
                        //插入节点的hash值 大于 当前节点
                        else if (ph < h)
                            //插入节点可能需要插入到当前节点的右子节点 或者 继续在右子树上查找
                            dir = 1;

                        //如果执行到 CASE3，说明当前插入节点的hash 与 当前节点的hash一致，会在case3 做出最终排序。最终
                        //拿到的dir 一定不是0，（-1， 1）
                        else if ((kc == null &&
                                  (kc = comparableClassFor(k)) == null) ||
                                 (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);

                        //xp 想要表示的是 插入节点的 父节点
                        TreeNode<K,V> xp = p;
                        //条件成立：说明当前p节点 即为插入节点的父节点
                        //条件不成立：说明p节点 底下还有层次，需要将p指向 p的左子节点 或者 右子节点，表示继续向下搜索。
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            //设置插入节点的父节点 为 当前节点
                            x.parent = xp;
                            //小于P节点，需要插入到P节点的左子节点
                            if (dir <= 0)
                                xp.left = x;

                                //大于P节点，需要插入到P节点的右子节点
                            else
                                xp.right = x;

                            //插入节点后，红黑树性质 可能会被破坏，所以需要调用 平衡方法
                            r = balanceInsertion(r, x);
                            break;
                        }
                    }
                }
            }
            //将r 赋值给 TreeBin对象的 root引用。
            this.root = r;
            assert checkInvariants(root);
        }

        /**
         * Acquires write lock for tree restructuring.
         */
        private final void lockRoot() {
            //条件成立：说明lockState 并不是 0，说明此时有其它读线程在treeBin红黑树中读取数据。
            if (!U.compareAndSwapInt(this, LOCKSTATE, 0, WRITER))
                contendedLock(); // offload to separate method
        }

        /**
         * Releases write lock for tree restructuring.
         */
        private final void unlockRoot() {
            lockState = 0;
        }

        /**
         * Possibly blocks awaiting root lock.
         */
        private final void contendedLock() {
            boolean waiting = false;
            //表示lock值
            int s;
            for (;;) {
                //~WAITER = 11111....01
                //条件成立：说明目前TreeBin中没有读线程在访问 红黑树
                //条件不成立：有线程在访问红黑树
                if (((s = lockState) & ~WAITER) == 0) {
                    //条件成立：说明写线程 抢占锁成功
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, WRITER)) {
                        if (waiting)
                            //设置TreeBin对象waiter 引用为null
                            waiter = null;
                        return;
                    }
                }
                //lock & 0000...10 = 0, 条件成立：说明lock 中 waiter 标志位 为0，此时当前线程可以设置为1了，然后将当前线程挂起。
                else if ((s & WAITER) == 0) {
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, s | WAITER)) {
                        waiting = true;
                        waiter = Thread.currentThread();
                    }
                }
                //条件成立：说明当前线程在CASE2中已经将 treeBin.waiter 设置为了当前线程，并且将lockState 中表示 等待者标记位的地方 设置为了1
                //这个时候，就让当前线程 挂起。。
                else if (waiting)
                    LockSupport.park(this);
            }
        }

        /**
         * Returns matching node or null if none. Tries to search
         * using tree comparisons from root, but continues linear
         * search when lock not available.
         */
        final Node<K,V> find(int h, Object k) {
            if (k != null) {

                //e 表示循环迭代的当前节点   迭代的是first引用的链表
                for (Node<K,V> e = first; e != null; ) {
                    //s 保存的是lock临时状态
                    //ek 链表当前节点 的key
                    int s; K ek;


                    //(WAITER|WRITER) => 0010 | 0001 => 0011
                    //lockState & 0011 != 0 条件成立：说明当前TreeBin 有等待者线程 或者 目前有写操作线程正在加锁
                    if (((s = lockState) & (WAITER|WRITER)) != 0) {
                        if (e.hash == h &&
                            ((ek = e.key) == k || (ek != null && k.equals(ek))))
                            return e;
                        e = e.next;
                    }

                    //前置条件：当前TreeBin中 等待者线程 或者 写线程 都没有
                    //条件成立：说明添加读锁成功
                    else if (U.compareAndSwapInt(this, LOCKSTATE, s,
                                                 s + READER)) {
                        TreeNode<K,V> r, p;
                        try {
                            //查询操作
                            p = ((r = root) == null ? null :
                                 r.findTreeNode(h, k, null));
                        } finally {
                            //w 表示等待者线程
                            Thread w;
                            //U.getAndAddInt(this, LOCKSTATE, -READER) == (READER|WAITER)
                            //1.当前线程查询红黑树结束，释放当前线程的读锁 就是让 lockstate 值 - 4
                            //(READER|WAITER) = 0110 => 表示当前只有一个线程在读，且“有一个线程在等待”
                            //当前读线程为 TreeBin中的最后一个读线程。

                            //2.(w = waiter) != null 说明有一个写线程在等待读操作全部结束。
                            if (U.getAndAddInt(this, LOCKSTATE, -READER) ==
                                (READER|WAITER) && (w = waiter) != null)
                                //使用unpark 让 写线程 恢复运行状态。
                                LockSupport.unpark(w);
                        }
                        return p;
                    }
                }
            }
            return null;
        }

        /**
         * Finds or adds a node.
         * @return null if added
         */
        final TreeNode<K,V> putTreeVal(int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            for (TreeNode<K,V> p = root;;) {
                int dir, ph; K pk;
                if (p == null) {
                    first = root = new TreeNode<K,V>(h, k, v, null, null);
                    break;
                }
                else if ((ph = p.hash) > h)
                    dir = -1;
                else if (ph < h)
                    dir = 1;
                else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                    return p;
                else if ((kc == null &&
                          (kc = comparableClassFor(k)) == null) ||
                         (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K,V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                             (q = ch.findTreeNode(h, k, kc)) != null) ||
                            ((ch = p.right) != null &&
                             (q = ch.findTreeNode(h, k, kc)) != null))
                            return q;
                    }
                    dir = tieBreakOrder(k, pk);
                }


                TreeNode<K,V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    //当前循环节点xp 即为 x 节点的爸爸

                    //x 表示插入节点
                    //f 老的头结点
                    TreeNode<K,V> x, f = first;
                    first = x = new TreeNode<K,V>(h, k, v, f, xp);

                    //条件成立：说明链表有数据
                    if (f != null)
                        //设置老的头结点的前置引用为 当前的头结点。
                        f.prev = x;


                    if (dir <= 0)
                        xp.left = x;
                    else
                        xp.right = x;


                    if (!xp.red)
                        x.red = true;
                    else {
                        //表示 当前新插入节点后，新插入节点 与 父节点 形成 “红红相连”
                        lockRoot();
                        try {
                            //平衡红黑树，使其再次符合规范。
                            root = balanceInsertion(root, x);
                        } finally {
                            unlockRoot();
                        }
                    }
                    break;
                }
            }
            assert checkInvariants(root);
            return null;
        }

        /**
         * Removes the given node, that must be present before this
         * call.  This is messier than typical red-black deletion code
         * because we cannot swap the contents of an interior node
         * with a leaf successor that is pinned by "next" pointers
         * that are accessible independently of lock. So instead we
         * swap the tree linkages.
         *
         * @return true if now too small, so should be untreeified
         */
        final boolean removeTreeNode(TreeNode<K,V> p) {
            TreeNode<K,V> next = (TreeNode<K,V>)p.next;
            TreeNode<K,V> pred = p.prev;  // unlink traversal pointers
            TreeNode<K,V> r, rl;
            if (pred == null)
                first = next;
            else
                pred.next = next;
            if (next != null)
                next.prev = pred;
            if (first == null) {
                root = null;
                return true;
            }
            if ((r = root) == null || r.right == null || // too small
                (rl = r.left) == null || rl.left == null)
                return true;
            lockRoot();
            try {
                TreeNode<K,V> replacement;
                TreeNode<K,V> pl = p.left;
                TreeNode<K,V> pr = p.right;
                if (pl != null && pr != null) {
                    TreeNode<K,V> s = pr, sl;
                    while ((sl = s.left) != null) // find successor
                        s = sl;
                    boolean c = s.red; s.red = p.red; p.red = c; // swap colors
                    TreeNode<K,V> sr = s.right;
                    TreeNode<K,V> pp = p.parent;
                    if (s == pr) { // p was s's direct parent
                        p.parent = s;
                        s.right = p;
                    }
                    else {
                        TreeNode<K,V> sp = s.parent;
                        if ((p.parent = sp) != null) {
                            if (s == sp.left)
                                sp.left = p;
                            else
                                sp.right = p;
                        }
                        if ((s.right = pr) != null)
                            pr.parent = s;
                    }
                    p.left = null;
                    if ((p.right = sr) != null)
                        sr.parent = p;
                    if ((s.left = pl) != null)
                        pl.parent = s;
                    if ((s.parent = pp) == null)
                        r = s;
                    else if (p == pp.left)
                        pp.left = s;
                    else
                        pp.right = s;
                    if (sr != null)
                        replacement = sr;
                    else
                        replacement = p;
                }
                else if (pl != null)
                    replacement = pl;
                else if (pr != null)
                    replacement = pr;
                else
                    replacement = p;
                if (replacement != p) {
                    TreeNode<K,V> pp = replacement.parent = p.parent;
                    if (pp == null)
                        r = replacement;
                    else if (p == pp.left)
                        pp.left = replacement;
                    else
                        pp.right = replacement;
                    p.left = p.right = p.parent = null;
                }

                root = (p.red) ? r : balanceDeletion(r, replacement);

                if (p == replacement) {  // detach pointers
                    TreeNode<K,V> pp;
                    if ((pp = p.parent) != null) {
                        if (p == pp.left)
                            pp.left = null;
                        else if (p == pp.right)
                            pp.right = null;
                        p.parent = null;
                    }
                }
            } finally {
                unlockRoot();
            }
            assert checkInvariants(root);
            return false;
        }

    /* ----------------Views -------------- */

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    /**表示sizeCtl属性在ConcurrentHashMap中内存偏移地址*/
    private static final long SIZECTL;
    /**表示transferIndex属性在ConcurrentHashMap中内存偏移地址*/
    private static final long TRANSFERINDEX;
    /**表示baseCount属性在ConcurrentHashMap中内存偏移地址*/
    private static final long BASECOUNT;
    /**表示cellsBusy属性在ConcurrentHashMap中内存偏移地址*/
    private static final long CELLSBUSY;
    /**表示cellValue属性在CounterCell中内存偏移地址*/
    private static final long CELLVALUE;
    /**表示数组第一个元素的偏移地址*/
    private static final long ABASE;
    private static final int ASHIFT;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ConcurrentHashMap.class;
            SIZECTL = U.objectFieldOffset
                (k.getDeclaredField("sizeCtl"));
            TRANSFERINDEX = U.objectFieldOffset
                (k.getDeclaredField("transferIndex"));
            BASECOUNT = U.objectFieldOffset
                (k.getDeclaredField("baseCount"));
            CELLSBUSY = U.objectFieldOffset
                (k.getDeclaredField("cellsBusy"));
            Class<?> ck = CounterCell.class;
            CELLVALUE = U.objectFieldOffset
                (ck.getDeclaredField("value"));
            Class<?> ak = Node[].class;
            ABASE = U.arrayBaseOffset(ak);
            //表示数组单元所占用空间大小,scale 表示Node[]数组中每一个单元所占用空间大小
            int scale = U.arrayIndexScale(ak);
            //1 0000 & 0 1111 = 0
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            //numberOfLeadingZeros() 这个方法是返回当前数值转换为二进制后，从高位到低位开始统计，看有多少个0连续在一块。
            //8 => 1000 numberOfLeadingZeros(8) = 28
            //4 => 100 numberOfLeadingZeros(4) = 29
            //ASHIFT = 31 - 29 = 2 ？？
            //ABASE + （5 << ASHIFT）
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
