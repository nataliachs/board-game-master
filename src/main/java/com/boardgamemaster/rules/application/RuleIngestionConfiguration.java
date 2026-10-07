package com.boardgamemaster.rules.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuleIngestionProperties.class)
class RuleIngestionConfiguration { }
