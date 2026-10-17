# 模块二学习记录：时间线

> 写在前面：仅做个人记录，不代表本模块内容

## 1. 简单概要

## 2. 模块学习收获

-1 用户记录数据库初步设计：
*主键：主键显式改掉了mp默认的雪花主键（分布式ID）采用postgreSQL里的identity自增主键，简化一些。
*记录内容：content使用TEXT类型，变长字符串，在postgre里与varchar性能类似，应用层对content校验限制（size注解，notblank注解）能让报错更好看。
*时间：
    -类型：a.数据库层用timestamptz，底层存的是UTC绝对时刻，输出时候按照当前会话时区偏移输出。
           b.java层使用OffsetDateTime，UTC加上偏移
    -列：两个列，一个starttime一个endtime endtime可为空，为空表示事件仍未结束。如果要表示瞬时事件，就让starttime == endtime
    -创建和更新时间，也是timestampz类型，默认为now()；mybaitis的MetaObjectHandler在写入前自动填入
    -deleted列：这里是为了做到“软删”，是为了防止引用到被彻底删除的行的时候出问题
        软删实现靠mybatisplus的注解@TableLogic(value = "false", delval = "true")，自动在查询里添加软删相关逻辑，还有自动写入，很好用
    -查询：
    场景例子：用户问：我在10/17日做了什么。
    区间用左闭右开，判断记录事件的时间区间【starttime-endtime）是否与这个时间区间重叠。查询方法在（TimelineEntryService,list)
    一个不知道是好是坏的影响：如果一个事件的endtime为空（表示事情仍未结束），每次后续查询都会被查到，可能不是用户想要的（这里考虑后续用户自行管理）


-2 flyway:类似于数据库的git。个人理解是，靠存sql脚本和对于脚本的历史记录表，实现了数据库版本管理，后续有需要再深入

-3 REST（Representational State Transfer，表述性状态转移）是种API设计风格
    具体来说就是HTTP方法表动作，url表操作的位置，例如GET    /api/timeline/1
-4 三种取参：
    @RequestBody，@PathVariable，@RequestParam


## 3. 踩坑与报错


