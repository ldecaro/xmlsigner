from amazoncorretto:8

RUN mkdir -p /u01/deploy

#Prepare Container for HSM client
ENV LD_LIBRARY_PATH="/opt/cloudhsm/lib"
RUN yum install -y shadow-utils
RUN mkdir -p /etc/security/limits.d/

RUN yum remove openssh-server

#Install HSM Client
ADD cloudhsm-client-latest.el7.x86_64.rpm /tmp/cloudhsm-client-latest.el7.x86_64.rpm
ADD cloudhsm-client-jce-latest.el7.x86_64.rpm /tmp/cloudhsm-client-jce-latest.el7.x86_64.rpm
RUN yum install -y /tmp/cloudhsm-client-latest.el7.x86_64.rpm
RUN yum install -y /tmp/cloudhsm-client-jce-latest.el7.x86_64.rpm
RUN rpm -qa | grep ssh | xargs rpm -e --nodeps
ENV PATH="/opt/cloudhsm/bin:${PATH}"


# Configure HSM Client
ADD resources/customerCA.crt /opt/cloudhsm/etc/customerCA.crt

WORKDIR /u01/deploy
ADD target/signer-1.0-SNAPSHOT.jar signer-1.0-SNAPSHOT.jar
ADD target/signer-1.0-SNAPSHOT.lib signer-1.0-SNAPSHOT.lib
#ADD target/signer-1.0-SNAPSHOT.lib/AmazonCorrettoCryptoProvider-1.1.1-linux-x86_64.jar /usr/lib/jvm/java-1.8.0-amazon-corretto/jre/lib/ext/AmazonCorrettoCryptoProvider-1.1.1-linux-x86_64.jar
ENTRYPOINT [ "sh", "-c", "java -Xmx512m -Xms512m -jar /u01/deploy/signer-1.0-SNAPSHOT.jar"]
