package com.schemaplexai.web.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置 - Exchange/Queue/Binding 声明
 */
@Configuration
public class RabbitMQConfig {

    // ========== Exchange ==========
    @Bean
    public TopicExchange agentExchange() {
        return new TopicExchange("sf.agent");
    }

    @Bean
    public TopicExchange workflowExchange() {
        return new TopicExchange("sf.workflow");
    }

    @Bean
    public FanoutExchange notificationExchange() {
        return new FanoutExchange("sf.notification");
    }

    @Bean
    public TopicExchange costExchange() {
        return new TopicExchange("sf.cost");
    }

    @Bean
    public TopicExchange qualityExchange() {
        return new TopicExchange("sf.quality");
    }

    /** Agent 执行事件 fanout exchange（多实例 SSE 广播） */
    @Bean
    public FanoutExchange agentExecEventExchange() {
        return new FanoutExchange("sf.agent.exec.event");
    }

    // ========== Queue ==========
    @Bean
    public Queue agentExecuteQueue() {
        return new Queue("sf.agent.execute", true);
    }

    @Bean
    public Queue agentResultQueue() {
        return new Queue("sf.agent.result", true);
    }

    @Bean
    public Queue workflowTriggerQueue() {
        return new Queue("sf.workflow.trigger", true);
    }

    @Bean
    public Queue notificationEmailQueue() {
        return new Queue("sf.notification.email", true);
    }

    @Bean
    public Queue notificationImQueue() {
        return new Queue("sf.notification.im", true);
    }

    @Bean
    public Queue costRecordQueue() {
        return new Queue("sf.cost.record", true);
    }

    @Bean
    public Queue qualityCheckQueue() {
        return new Queue("sf.quality.check", true);
    }

    /** Agent 团队上下文共享队列（Sub-Agent 产出后通知其他成员） */
    @Bean
    public Queue agentTeamContextQueue() {
        return new Queue("sf.agent.team.context", true);
    }

    /** 每个实例独占一个匿名队列，接收执行事件广播 */
    @Bean
    public Queue agentExecEventQueue() {
        return new AnonymousQueue();
    }

    // ========== Binding ==========
    @Bean
    public Binding agentExecuteBinding() {
        return BindingBuilder.bind(agentExecuteQueue()).to(agentExchange()).with("agent.execute.*");
    }

    @Bean
    public Binding agentResultBinding() {
        return BindingBuilder.bind(agentResultQueue()).to(agentExchange()).with("agent.result.*");
    }

    @Bean
    public Binding workflowTriggerBinding() {
        return BindingBuilder.bind(workflowTriggerQueue()).to(workflowExchange()).with("workflow.trigger.*");
    }

    @Bean
    public Binding notificationEmailBinding() {
        return BindingBuilder.bind(notificationEmailQueue()).to(notificationExchange());
    }

    @Bean
    public Binding notificationImBinding() {
        return BindingBuilder.bind(notificationImQueue()).to(notificationExchange());
    }

    @Bean
    public Binding costRecordBinding() {
        return BindingBuilder.bind(costRecordQueue()).to(costExchange()).with("cost.record.*");
    }

    @Bean
    public Binding qualityCheckBinding() {
        return BindingBuilder.bind(qualityCheckQueue()).to(qualityExchange()).with("quality.check.*");
    }

    /** 团队上下文共享：agent.team.context.{teamAgentId} → sf.agent.team.context */
    @Bean
    public Binding agentTeamContextBinding() {
        return BindingBuilder.bind(agentTeamContextQueue()).to(agentExchange()).with("agent.team.context.#");
    }

    @Bean
    public Binding agentExecEventBinding() {
        return BindingBuilder.bind(agentExecEventQueue()).to(agentExecEventExchange());
    }

    /** 使用Jackson序列化消息 */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
