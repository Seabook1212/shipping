# FROM java:openjdk-8-alpine
# FROM eclipse-temurin:8-jdk-alpine
FROM eclipse-temurin:17-jre

ENV	SERVICE_USER=myuser \
	SERVICE_UID=10001 \
	SERVICE_GROUP=mygroup \
	SERVICE_GID=10001

RUN	groupadd -g ${SERVICE_GID} ${SERVICE_GROUP} && \
	useradd -g ${SERVICE_GROUP} -u ${SERVICE_UID} -M -s /usr/sbin/nologin ${SERVICE_USER} && \
	apt-get update && \
	apt-get install -y libcap2-bin && \
	setcap 'cap_net_bind_service=+ep' $(readlink -f $(which java)) && \
	apt-get clean && \
	rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src/app
# COPY *.jar ./app.jar
COPY ./target/*.jar ./app.jar

RUN	chown -R ${SERVICE_USER}:${SERVICE_GROUP} ./app.jar

USER ${SERVICE_USER}
# USER root

ENTRYPOINT ["java","-Djava.security.egd=file:/dev/urandom","-jar","./app.jar", "--port=80"]
#image: weaveworksdemos/shipping:0.4.8
#image: seabook1111/shipping:inject-1-3-v7