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
package net.hasor.dataql.runtime.inset;
import net.hasor.dataql.ProcessException;
import net.hasor.dataql.runtime.mem.LocalData;
import net.hasor.dataql.runtime.mem.MemStack;
import net.hasor.dataql.runtime.process.InsetProcess;
import net.hasor.dataql.runtime.process.InstSequence;
import net.hasor.dataql.runtime.ProcessContet;
/**
 * LDC_B，输出一个 boolean 到栈。
 * @author 赵永春(zyc@hasor.net)
 * @version : 2017-07-19
 */
class LDC_B implements InsetProcess {
    @Override
    public int getOpcode() {
        return LDC_B;
    }
    @Override
    public void doWork(InstSequence sequence, MemStack memStack, LocalData local, ProcessContet context) throws ProcessException {
        Boolean value = sequence.currentInst().getBoolean(0);
        memStack.push(value);
    }
}