

```java
// 表达式的简化：
() -> {}
() -> x>6	//省去{ return xx; }
(x, y) -> x.compareTo(y)
x -> x.getName().toUpperCase()
    
stream().filter(lambda表达式
               ).map(lambda表达式);
```



```java
//R apply(T t);函数型接口，一个参数，一个返回值
Function<String,Integer> function = t ->{return t.length();};
System.out.println(function.apply("abcd"));

//boolean test(T t);断定型接口，一个参数，返回boolean
Predicate<String> predicate = t->{return t.startsWith("a");};
System.out.println(predicate.test("a"));

// void accept(T t);消费型接口，一个参数，没有返回值
Consumer<String> consumer = t->{
    System.out.println(t);
};
consumer.accept("javaXXXX");

//T get(); 供给型接口，无参数，有返回值
Supplier<String> supplier =()->{return UUID.randomUUID().toString();};
System.out.println(supplier.get());
 
```





```java
筛选	filter	=	where	
聚合	max/min/count
收集	collect	=	select
排序	sorted	=	order by
遍历	forEach	=	读取resultStatement
```





```java
List<String> list = Arrays.asList("abc", "admin", "root", "roottt", "robot");
Optional<String> max = list.stream().max(Comparator.comparing(String::length));
System.out.println("最长的字符串：" + max.get());
```

创建一个Stream：一个数据源（数组、集合）

中间操作：一个中间操作，处理数据源数据

终止操作：一个终止操作，执行中间操作链，产生结果