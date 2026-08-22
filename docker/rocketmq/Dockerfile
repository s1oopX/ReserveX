FROM apache/rocketmq:5.5.0

USER root

ADD https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.84/bcprov-jdk18on-1.84.jar /tmp/bcprov.jar
ADD https://repo1.maven.org/maven2/com/alibaba/fastjson/1.2.83_noneautotype/fastjson-1.2.83_noneautotype.jar /tmp/fastjson.jar

RUN echo "64d6c5a6121fcd927152dd182cbed39afe0fda641a970d9bcc0c9cb1858b2731  /tmp/bcprov.jar" | sha256sum -c - \
    && echo "10966bdcb594e156627b97559458433b096905358aa61f5f0ad9b3f1bba04166  /tmp/fastjson.jar" | sha256sum -c - \
    && rm "${ROCKETMQ_HOME}/lib/bcprov-jdk18on-1.83.jar" "${ROCKETMQ_HOME}/lib/fastjson-1.2.83.jar" \
    && mv /tmp/bcprov.jar "${ROCKETMQ_HOME}/lib/bcprov-jdk18on-1.84.jar" \
    && mv /tmp/fastjson.jar "${ROCKETMQ_HOME}/lib/fastjson-1.2.83_noneautotype.jar" \
    && chown rocketmq:rocketmq "${ROCKETMQ_HOME}/lib/bcprov-jdk18on-1.84.jar" \
       "${ROCKETMQ_HOME}/lib/fastjson-1.2.83_noneautotype.jar"

USER rocketmq
