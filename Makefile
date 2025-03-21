.PHONY: build-consumer build-producer e2etest test all clean

# 운영체제 감지
ifeq ($(OS),Windows_NT)
    GRADLEW = gradlew.bat
else
    GRADLEW = ./gradlew
endif

all: build-consumer build-producer

build-consumer:
	$(GRADLEW) :consumer:build --refresh-dependencies
	cd consumer && docker build -t consumer:0.0.0 --no-cache .

build-producer:
	$(GRADLEW) :producer:build --refresh-dependencies
	cd producer && docker build -t producer:0.0.0 --no-cache .

e2etest:
	$(GRADLEW) :integrationtest:test --rerun-tasks

# test 타겟 추가 - e2etest와 동일한 명령 실행
test:
	$(GRADLEW) :integrationtest:test --rerun-tasks