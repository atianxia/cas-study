package org.apereo.cas.config;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import org.apereo.cas.CipherExecutor;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.web.flow.CasDefaultFlowUrlHandler;
import org.apereo.cas.web.flow.CasWebflowConfigurer;
import org.apereo.cas.web.flow.DefaultWebflowConfigurer;
import org.apereo.cas.web.flow.LogoutConversionService;
import org.apereo.spring.webflow.plugin.ClientFlowExecutionRepository;
import org.apereo.spring.webflow.plugin.EncryptedTranscoder;
import org.apereo.spring.webflow.plugin.Transcoder;
import org.cryptacular.bean.CipherBean;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.binding.convert.ConversionService;
import org.springframework.binding.expression.ExpressionParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.webflow.config.FlowBuilderServicesBuilder;
import org.springframework.webflow.config.FlowDefinitionRegistryBuilder;
import org.springframework.webflow.config.FlowExecutorBuilder;
import org.springframework.webflow.context.servlet.FlowUrlHandler;
import org.springframework.webflow.conversation.impl.SessionBindingConversationManager;
import org.springframework.webflow.definition.registry.FlowDefinitionRegistry;
import org.springframework.webflow.engine.builder.ViewFactoryCreator;
import org.springframework.webflow.engine.builder.support.FlowBuilderServices;
import org.springframework.webflow.engine.impl.FlowExecutionImplFactory;
import org.springframework.webflow.execution.repository.impl.DefaultFlowExecutionRepository;
import org.springframework.webflow.execution.repository.snapshot.SerializedFlowExecutionSnapshotFactory;
import org.springframework.webflow.executor.FlowExecutor;
import org.springframework.webflow.executor.FlowExecutorImpl;
import org.springframework.webflow.expression.spel.WebFlowSpringELExpressionParser;
import org.springframework.webflow.mvc.builder.MvcViewFactoryCreator;
import org.springframework.webflow.mvc.servlet.FlowHandler;
import org.springframework.webflow.mvc.servlet.FlowHandlerAdapter;
import org.springframework.webflow.mvc.servlet.FlowHandlerMapping;

import javax.naming.OperationNotSupportedException;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * This is {@link CasWebflowContextConfiguration} that attempts to create Spring-managed beans
 * backed by external configuration.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Configuration("casWebflowContextConfiguration")
@EnableConfigurationProperties(CasConfigurationProperties.class)
public class CasWebflowContextConfiguration {

    private static final int LOGOUT_FLOW_HANDLER_ORDER = 3;

    private static final String BASE_CLASSPATH_WEBFLOW = "classpath*:/webflow";

    @Autowired
    private CasConfigurationProperties casProperties;

    @Autowired
    @Qualifier("registeredServiceViewResolver")
    private ViewResolver registeredServiceViewResolver;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("webflowCipherExecutor")
    private CipherExecutor webflowCipherExecutor;

    @Autowired
    @Qualifier("authenticationThrottle")
    private HandlerInterceptor authenticationThrottle;
    
    @Bean
    public ExpressionParser expressionParser() {
        final WebFlowSpringELExpressionParser parser = new WebFlowSpringELExpressionParser(
                new SpelExpressionParser(),
                logoutConversionService());

        return parser;
    }

    @Bean
    public ConversionService logoutConversionService() {
        return new LogoutConversionService();
    }

    @RefreshScope
    @Bean
    public ViewFactoryCreator viewFactoryCreator() {
        final MvcViewFactoryCreator resolver = new MvcViewFactoryCreator();
        resolver.setViewResolvers(ImmutableList.of(this.registeredServiceViewResolver));
        return resolver;
    }

    @Bean
    public FlowUrlHandler loginFlowUrlHandler() {
        return new CasDefaultFlowUrlHandler();
    }
    
    @Bean
    public FlowUrlHandler logoutFlowUrlHandler() {
        final CasDefaultFlowUrlHandler handler = new CasDefaultFlowUrlHandler();
        handler.setFlowExecutionKeyParameter("RelayState");
        return handler;
    }
    
    @RefreshScope
    @Bean
    public HandlerAdapter logoutHandlerAdapter() {
        final FlowHandlerAdapter handler = new FlowHandlerAdapter() {
            @Override
            public boolean supports(final Object handler) {
                return super.supports(handler) && ((FlowHandler) handler)
                        .getFlowId().equals(CasWebflowConfigurer.FLOW_ID_LOGOUT);
            }
        };
        handler.setFlowExecutor(logoutFlowExecutor());
        handler.setFlowUrlHandler(logoutFlowUrlHandler());
        return handler;
    }
    
    @RefreshScope
    @Bean
    public CipherBean loginFlowCipherBean() {
        try {
            return new CipherBean() {
                @Override
                public byte[] encrypt(final byte[] bytes) {
                    return (byte[]) CasWebflowContextConfiguration.this.webflowCipherExecutor.encode(bytes);
                }

                @Override
                public void encrypt(final InputStream inputStream, final OutputStream outputStream) {
                    throw new RuntimeException(
                            new OperationNotSupportedException("Encrypting input stream is not supported"));
                }

                @Override
                public byte[] decrypt(final byte[] bytes) {
                    return (byte[]) CasWebflowContextConfiguration.this.webflowCipherExecutor.decode(bytes);
                }

                @Override
                public void decrypt(final InputStream inputStream, final OutputStream outputStream) {
                    throw new RuntimeException(
                            new OperationNotSupportedException("Decrypting input stream is not supported"));
                }
            };
        } catch (final Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @RefreshScope
    @Bean
    public FlowBuilderServices builder() {
        final FlowBuilderServicesBuilder builder = new FlowBuilderServicesBuilder(this.applicationContext);
        builder.setViewFactoryCreator(viewFactoryCreator());
        builder.setExpressionParser(expressionParser());
        builder.setDevelopmentMode(casProperties.getWebflow().isRefresh());
        return builder.build();
    }
    
    @Bean
    public Transcoder loginFlowStateTranscoder() {
        try {
            return new EncryptedTranscoder(loginFlowCipherBean());
        } catch (final Exception e) {
            throw new BeanCreationException(e.getMessage(), e);
        }
    }
    
    @Bean
    public HandlerAdapter loginHandlerAdapter() {
        final FlowHandlerAdapter handler = new FlowHandlerAdapter() {
            @Override
            public boolean supports(final Object handler) {
                return super.supports(handler) && ((FlowHandler) handler)
                        .getFlowId().equals(CasWebflowConfigurer.FLOW_ID_LOGIN);
            }
        };
        handler.setFlowExecutor(loginFlowExecutor());
        handler.setFlowUrlHandler(loginFlowUrlHandler());
        return handler;
    }

    @RefreshScope
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        final LocaleChangeInterceptor bean = new LocaleChangeInterceptor();
        bean.setParamName(this.casProperties.getLocale().getParamName());
        return bean;
    }
    
    @Bean
    public HandlerMapping logoutFlowHandlerMapping() {
        final FlowHandlerMapping handler = new FlowHandlerMapping();
        handler.setOrder(LOGOUT_FLOW_HANDLER_ORDER);
        handler.setFlowRegistry(logoutFlowRegistry());
        final Object[] interceptors = new Object[]{localeChangeInterceptor()};
        handler.setInterceptors(interceptors);
        return handler;
    }
    
    @Bean
    public HandlerMapping loginFlowHandlerMapping() {
        final FlowHandlerMapping handler = new FlowHandlerMapping();
        handler.setOrder(LOGOUT_FLOW_HANDLER_ORDER - 1);
        handler.setFlowRegistry(loginFlowRegistry());
        final Object[] interceptors = new Object[]{localeChangeInterceptor(), this.authenticationThrottle};
        handler.setInterceptors(interceptors);
        return handler;
    }
    
    @RefreshScope
    @Bean
    public FlowExecutor logoutFlowExecutor() {
        final FlowExecutorBuilder builder = new FlowExecutorBuilder(logoutFlowRegistry(), this.applicationContext);
        builder.setAlwaysRedirectOnPause(casProperties.getWebflow().isAlwaysPauseRedirect());
        builder.setRedirectInSameState(casProperties.getWebflow().isRedirectSameState());
        return builder.build();
    }
    
    @Bean
    public FlowDefinitionRegistry logoutFlowRegistry() {
        final FlowDefinitionRegistryBuilder builder = new FlowDefinitionRegistryBuilder(this.applicationContext, builder());
        builder.setBasePath(BASE_CLASSPATH_WEBFLOW);
        builder.addFlowLocationPattern("/logout/*-webflow.xml");
        return builder.build();
    }

    @Bean
    public FlowDefinitionRegistry loginFlowRegistry() {
        final FlowDefinitionRegistryBuilder builder =
                new FlowDefinitionRegistryBuilder(this.applicationContext, builder());
        builder.setBasePath(BASE_CLASSPATH_WEBFLOW);
        builder.addFlowLocationPattern("/login/*-webflow.xml");
        return builder.build();
    }
    
    @RefreshScope
    @Bean
    public FlowExecutor loginFlowExecutor() {
        final FlowDefinitionRegistry loginFlowRegistry = loginFlowRegistry();

        if (casProperties.getWebflow().getSession().isStorage()) {
            final SessionBindingConversationManager conversationManager = new SessionBindingConversationManager();
            conversationManager.setLockTimeoutSeconds(casProperties.getWebflow().getSession().getLockTimeout());
            conversationManager.setMaxConversations(casProperties.getWebflow().getSession().getMaxConversations());

            final FlowExecutionImplFactory executionFactory = new FlowExecutionImplFactory();

            final SerializedFlowExecutionSnapshotFactory flowExecutionSnapshotFactory =
                    new SerializedFlowExecutionSnapshotFactory(executionFactory, loginFlowRegistry());
            flowExecutionSnapshotFactory.setCompress(casProperties.getWebflow().getSession().isCompress());

            final DefaultFlowExecutionRepository repository = new DefaultFlowExecutionRepository(conversationManager,
                    flowExecutionSnapshotFactory);
            executionFactory.setExecutionKeyFactory(repository);
            return new FlowExecutorImpl(loginFlowRegistry, executionFactory, repository);
        }

        final ClientFlowExecutionRepository repository = new ClientFlowExecutionRepository();
        repository.setFlowDefinitionLocator(loginFlowRegistry);
        repository.setTranscoder(loginFlowStateTranscoder());

        final FlowExecutionImplFactory factory = new FlowExecutionImplFactory();
        factory.setExecutionKeyFactory(repository);
        repository.setFlowExecutionFactory(factory);
        return new FlowExecutorImpl(loginFlowRegistry, factory, repository);
    }

    @ConditionalOnMissingBean(name = "defaultWebflowConfigurer")
    @Bean
    public CasWebflowConfigurer defaultWebflowConfigurer() {
        final DefaultWebflowConfigurer c = new DefaultWebflowConfigurer();
        c.setLoginFlowDefinitionRegistry(loginFlowRegistry());
        c.setLogoutFlowDefinitionRegistry(logoutFlowRegistry());
        c.setFlowBuilderServices(builder());
        return c;
    }
}

