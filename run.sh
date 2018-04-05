#!/usr/bin/env bash
java -Djava.security.egd=file:\dev\.\urandom -javaagent:target\lib\jetty-alpn-agent-2.0.6.jar -jar target\neovestfxdataserver.jar
