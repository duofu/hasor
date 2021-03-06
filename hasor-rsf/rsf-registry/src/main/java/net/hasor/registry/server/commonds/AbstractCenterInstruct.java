/*
 * Copyright 2008-2009 the original author or authors.
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
package net.hasor.registry.server.commonds;
import net.hasor.registry.RegistryCenter;
import net.hasor.registry.common.InstanceInfo;
import net.hasor.tconsole.CommandExecutor;
import net.hasor.tconsole.launcher.CmdRequest;
/**
 * center 命令基类。
 * @version : 2016年4月3日
 * @author 赵永春(zyc @ hasor.net)
 */
public abstract class AbstractCenterInstruct implements CommandExecutor {
    @Override
    public final String doCommand(CmdRequest request) throws Throwable {
        RegistryCenter center = request.getFinder().getAppContext().getInstance(RegistryCenter.class);
        if (center == null) {
            return "[ERROR] the service 'RegistryCenter' is Undefined.";
        }
        return this.doCommand(center.getInstanceInfo(), request);
    }
    public abstract String doCommand(InstanceInfo instance, CmdRequest request) throws Throwable;
}