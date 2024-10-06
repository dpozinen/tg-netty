FROM gradle:jdk17 AS build

WORKDIR /usr/app/
COPY . .
RUN gradle build -x test

FROM ubuntu:latest
LABEL authors="dpozinen"

ARG PROJECT_VERSION='1.0-SNAPSHOT'

RUN apt -y update
RUN apt -y install openjdk-17-jre

WORKDIR /opt/app/

COPY --from=build "/usr/app//build/distributions/netty-tg-$PROJECT_VERSION.tar" .

RUN tar -xvf netty-tg-$PROJECT_VERSION.tar

WORKDIR /opt/app/netty-tg-$PROJECT_VERSION/bin

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["./netty-tg"]
