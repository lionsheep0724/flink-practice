# OpenJDK 17을 사용하는 경량 베이스 이미지 사용
FROM openjdk:17-jdk-alpine

# jar 파일을 복사할 작업 디렉토리 생성
WORKDIR /app

# 빌드된 jar 파일을 컨테이너로 복사 (Gradle 빌드 시, 보통 build/libs/consumer-0.0.1-SNAPSHOT.jar 경로에 생성됨)
COPY build/libs/consumer-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/java.lang=ALL-UNNAMED"

# JVM 옵션 추가
ENV JAVA_OPTS="--add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED"

# 컨테이너 시작 시 jar 파일 실행
CMD ["sh", "-c", "java $JAVA_OPTS -jar ./app.jar"]
