package com.schemaplexai.service.workflow.flowable;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;

import java.util.List;
import java.util.Map;

/**
 * Flowable 扩展元素工具类
 *
 * <p>用于从 DelegateExecution / FlowElement 中读取自定义的扩展属性（nodeId, nodeType, nodeConfig 等）。
 */
public final class FlowableExtensionUtil {

    private static final String NAMESPACE = "http://schemaplexai.com/bpmn";

    private FlowableExtensionUtil() {
    }

    /**
     * 从当前执行对象的 FlowElement 上读取扩展元素值
     */
    public static String getExtensionValue(DelegateExecution execution, String name) {
        FlowElement flowElement = execution.getCurrentFlowElement();
        if (flowElement == null) {
            return null;
        }
        return getExtensionValue(flowElement, name);
    }

    /**
     * 从 FlowElement 上读取扩展元素值
     */
    public static String getExtensionValue(FlowElement flowElement, String name) {
        Map<String, List<ExtensionElement>> extensions = flowElement.getExtensionElements();
        if (extensions == null) {
            return null;
        }
        List<ExtensionElement> elements = extensions.get(name);
        if (elements == null || elements.isEmpty()) {
            return null;
        }
        return elements.get(0).getElementText();
    }
}
