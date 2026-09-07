package com.portfolio.invest.application.skill;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 单例 ClasspathSkillRepository：内部按 URI 引用计数挂载 JAR 虚拟 FS，随 Spring 生命周期管理，勿每次 build 时 new/close。 */
@Configuration
public class SkillRepositoryConfig {

    @Bean
    public ClasspathSkillRepository builtInSkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills");
    }
}
