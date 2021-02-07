# 20210129 改动记录

当前模块是从hadoop最新代码(应该超过 tag = rel/release-3.3.0)的module模块代码改造而来。对应commmit为：
* trunk                                       fa15594ae60 YARN-10600. Convert root queue in fs2cs weight mode conversion. Contributed by Benjamin Teke.

修改内容涉及
* 修改了hadoop-tools pom，添加hadoop-aliyun module
* 修改模块本身pom，修改为对应的version，并明确指定 `aliyun-sdk-oss`依赖版本
* 去除所有`org.apache.hadoop.thirdparty.` 开头的代码
* 修改`import org.apache.commons.lang3.` 为 `import org.apache.commons.lang.`
* 补充`org.apache.hadoop.util`目录下的两个类

编译命令: `mvn clean package -pl hadoop-tools/hadoop-aliyun`

https://repo1.maven.org/maven2/com/aliyun/oss/aliyun-sdk-oss/3.4.1/

项目运行需要补充的依赖包

* https://nexus.leyantech.com/repository/maven-public/org/ini4j/ini4j/0.5.4/ini4j-0.5.4.jar
* https://nexus.leyantech.com/repository/maven-public/org/jacoco/org.jacoco.agent/0.8.5/org.jacoco.agent-0.8.5-runtime.jar
* https://nexus.leyantech.com/repository/maven-public/com/aliyun/aliyun-java-sdk-ram/3.1.0/aliyun-java-sdk-ram-3.1.0.jar
* https://nexus.leyantech.com/repository/maven-public/com/aliyun/aliyun-java-sdk-kms/2.11.0/aliyun-java-sdk-kms-2.11.0.jar
* https://nexus.leyantech.com/repository/maven-public/com/aliyun/aliyun-java-sdk-core/4.5.10/aliyun-java-sdk-core-4.5.10.jar
* https://nexus.leyantech.com/repository/maven-public/com/aliyun/oss/aliyun-sdk-oss/3.11.2/aliyun-sdk-oss-3.11.2.jar
