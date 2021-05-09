#!/bin/bash

function update_java() {
    pushd plugin
    docker run --rm -u gradle -v "$PWD":/home/gradle/project -w /home/gradle/project registry1.dso.mil/ironbank/opensource/gradle/gradle-jdk8 gradle build
    mkdir -p build/docker
    cp build/libs/platform-one-sso-1.0.0-all.jar build/docker/p1.jar
    popd
}

function release() {
    update_java
    cp plugin/build/docker/p1.jar ../deploy/resources/p1-sso-plugin.jar
    cp plugin/build/docker/p1.jar ../chart/resources/p1-sso-plugin.jar
}

# function buildProd() {
    
# }

function build() {
    docker kill p1-keycloak
    docker rm p1-keycloak

    DOCKER_BUILDKIT=1 docker build -f Dockerfile.dev -t p1-keycloak:dev-latest ../

    docker run -p 8443:8443 -p 5005:5005 \
    --name p1-keycloak \
    -v $PWD/plugin/build/docker:/opt/jboss/keycloak/standalone/deployments \
    -v $PWD/plugin/src/main/resources/theme/p1-sso:/opt/jboss/keycloak/themes/p1-sso-live-dev \
    p1-keycloak:dev-latest
}

case "$1" in

    update)
        update_java
        ;;

    release)
        update_java
        release
        ;;

    *)
        update_java
        build
        ;;

esac