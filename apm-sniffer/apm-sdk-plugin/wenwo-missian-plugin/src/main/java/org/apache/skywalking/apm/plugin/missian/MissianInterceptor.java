/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.missian;

import com.missian.client.context.MissianContext;
import com.missian.client.sync.SyncMissianProxy;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 自定义missian链路追踪插件
 *
 * @author ruanjl
 */
public class MissianInterceptor implements InstanceMethodsAroundInterceptor {

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) {
        SyncMissianProxy missianProxy = (SyncMissianProxy)objInst;
        String host = missianProxy.getHost();
        int port = missianProxy.getPort();
        Method declareMethod = (Method) allArguments[1];
        String className = declareMethod.getDeclaringClass().getName();
        String methodName = declareMethod.getName();
        String methodSignature = getMethodSignature(declareMethod);

        String remotePeer = host + ":" + port;
        String operationName = className + "." + methodName + methodSignature;

        final ContextCarrier contextCarrier = new ContextCarrier();
        AbstractSpan span = ContextManager.createExitSpan(operationName, contextCarrier, remotePeer);
        CarrierItem next = contextCarrier.items();
        MissianContext rpcContext = MissianContext.getContext();
        Map<String, String> contextInfo = rpcContext.getContextInfo();
        contextInfo.put("host", host);
        contextInfo.put("port", String.valueOf(port));
        contextInfo.put("operationName", operationName);
        while (next.hasNext()) {
            next = next.next();
            contextInfo.put(next.getHeadKey(), next.getHeadValue());
        }
        Tags.URL.set(span, "tcp://" + remotePeer + "/" + operationName);
        span.setComponent(ComponentsDefine.MISSiAN);
        SpanLayer.asRPCFramework(span);
    }

    private String getMethodSignature(Method declareMethod) {
        StringBuilder methodSignature = new StringBuilder("(");
        Class<?>[] parameterTypes = declareMethod.getParameterTypes();
        int length = parameterTypes.length;
        if (length > 0) {
            for (int i = 0; i < length - 1; i++) {
                methodSignature.append(parameterTypes[i].getName()).append(",");
            }
            methodSignature.append(parameterTypes[length - 1].getName());
        }
        methodSignature.append(")");
        return methodSignature.toString();
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
                              Class<?>[] argumentsTypes, Object ret) {
        ContextManager.stopSpan();
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {

        dealException(t);
    }

    /**
     * Log the throwable, which occurs in Dubbo RPC service.
     */
    private void dealException(Throwable throwable) {
        AbstractSpan span = ContextManager.activeSpan();
        span.errorOccurred();
        span.log(throwable);
    }
}
