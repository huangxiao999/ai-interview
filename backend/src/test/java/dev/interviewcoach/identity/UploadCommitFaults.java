package dev.interviewcoach.identity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 仅测试装配：让真实 PG 已提交后丢失提交确认，不能以仓储替身证明提交结果。 */
@TestConfiguration(proxyBeanMethods = false)
class UploadCommitFaults {
    static final AtomicBoolean LOSE_NEXT_CONFIRM_COMMIT = new AtomicBoolean();

    @Bean
    static BeanPostProcessor uploadCommitFailure() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source)) return bean;
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, args) -> {
                    try {
                        Object value = m.invoke(source, args);
                        if (value instanceof Connection connection && m.getName().equals("getConnection")) {
                            var confirmation = new AtomicBoolean();
                            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
                                try {
                                    if (cm.getName().equals("prepareStatement") && ca[0] instanceof String sql
                                            && sql.startsWith("UPDATE material_upload SET state='CONFIRMED'")) confirmation.set(true);
                                    Object result = cm.invoke(connection, ca);
                                    if (cm.getName().equals("commit") && confirmation.get()
                                            && LOSE_NEXT_CONFIRM_COMMIT.compareAndSet(true, false)) {
                                        throw new SQLException("Synthetic lost commit acknowledgement", "08006");
                                    }
                                    return result;
                                } catch (InvocationTargetException ex) { throw ex.getCause(); }
                            });
                        }
                        return value;
                    } catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
            }
        };
    }
}
