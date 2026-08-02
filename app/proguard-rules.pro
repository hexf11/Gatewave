# 保留崩溃堆栈所需的源文件和行号信息。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 设置值使用枚举名称持久化，确保后续版本可以读取已有配置。
-keepclassmembers enum com.hexf11.gatewave.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
