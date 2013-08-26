/*
 * Copyright 2008-2009 the original ������(zyc@hasor.net).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hasor.mvc.controller.support;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.hasor.Hasor;
import org.hasor.context.AppContext;
import org.hasor.mvc.controller.ActionException;
import org.more.util.MatchUtils;
import com.google.inject.Inject;
/**
 * action���ܵ���ڡ�
 * @version : 2013-5-11
 * @author ������ (zyc@hasor.net)
 */
class ActionController extends HttpServlet {
    private static final long serialVersionUID = -2579757349905408506L;
    @Inject
    private AppContext        appContext       = null;
    private ActionManager     actionManager    = null;
    private ActionSettings    actionSettings   = null;
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        this.actionManager = appContext.getInstance(ActionManager.class);
        this.actionManager.initManager(appContext);
        this.actionSettings = appContext.getInstance(ActionSettings.class);
        Hasor.info("ActionController intercept %s.", actionSettings.getIntercept());
    }
    public boolean testURL(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (MatchUtils.matchWild(actionSettings.getIntercept(), requestPath) == false)
            return false;
        return true;
    }
    //
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (MatchUtils.matchWild(actionSettings.getIntercept(), requestPath) == false)
            return;
        //
        //1.��������ַ���
        ActionDefineImpl define = getActionDefine(requestPath, request.getMethod());
        if (define == null) {
            String logInfo = String.format("%s action is not defined.", requestPath);
            throw new ActionException(logInfo);
        }
        //3.ִ�е���
        doInvoke(define, request, response);
    }
    private ActionDefineImpl getActionDefine(String requestPath, String httpMethod) {
        //1.��������ַ���
        String actionNS = requestPath.substring(0, requestPath.lastIndexOf("/") + 1);
        String actionInvoke = requestPath.substring(requestPath.lastIndexOf("/") + 1);
        String actionMethod = actionInvoke.split("\\.")[0];
        //2.��ȡ ActionInvoke
        ActionNameSpace nameSpace = actionManager.findNameSpace(actionNS);
        if (nameSpace != null)
            return nameSpace.getActionByName(actionMethod);
        return null;
    }
    //
    private void doInvoke(ActionDefineImpl define, ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
        try {
            define.createInvoke(servletRequest, servletResponse).invoke();
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();//���쳣
            if (target instanceof ServletException)
                throw (ServletException) target;
            if (target instanceof IOException)
                throw (IOException) target;
            throw new ServletException(target);
        }
    }
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    /** Ϊת���ṩ֧�� */
    public RequestDispatcher getRequestDispatcher(String path, String httpMethod) {
        // TODO ��Ҫ�����������Ƿ����Servlet�淶����request���������Ҳ��Ҫ��飩
        final String newRequestUri = path;
        //1.��������ַ���
        final ActionDefineImpl define = getActionDefine(path, httpMethod);
        if (define == null)
            return null;
        else
            return new RequestDispatcher() {
                public void include(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
                    servletRequest.setAttribute(REQUEST_DISPATCHER_REQUEST, Boolean.TRUE);
                    /*ִ��servlet*/
                    try {
                        doInvoke(define, servletRequest, servletResponse);
                    } finally {
                        servletRequest.removeAttribute(REQUEST_DISPATCHER_REQUEST);
                    }
                }
                public void forward(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
                    if (servletResponse.isCommitted() == true)
                        throw new ServletException("Response has been committed--you can only call forward before committing the response (hint: don't flush buffers)");
                    /*��ջ���*/
                    servletResponse.resetBuffer();
                    ServletRequest requestToProcess;
                    if (servletRequest instanceof HttpServletRequest) {
                        requestToProcess = new RequestDispatcherRequestWrapper(servletRequest, newRequestUri);
                    } else {
                        //�������֮�²���ִ����δ��롣
                        requestToProcess = servletRequest;
                    }
                    /*ִ��ת��*/
                    servletRequest.setAttribute(REQUEST_DISPATCHER_REQUEST, Boolean.TRUE);
                    try {
                        doInvoke(define, requestToProcess, servletResponse);
                    } finally {
                        servletRequest.removeAttribute(REQUEST_DISPATCHER_REQUEST);
                    }
                }
            };
    }
    /** ʹ��RequestDispatcherRequestWrapper�ദ��request.getRequestURI�����ķ���ֵ*/
    public static final String REQUEST_DISPATCHER_REQUEST = "javax.servlet.forward.servlet_path";
    private static class RequestDispatcherRequestWrapper extends HttpServletRequestWrapper {
        private final String newRequestUri;
        public RequestDispatcherRequestWrapper(ServletRequest servletRequest, String newRequestUri) {
            super((HttpServletRequest) servletRequest);
            this.newRequestUri = newRequestUri;
        }
        public String getRequestURI() {
            return newRequestUri;
        }
    }
}