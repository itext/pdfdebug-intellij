/*
 * This file is part of the iText (R) project.
 * Copyright (c) 2007-2020 iText Group NV
 * Authors: Bruno Lowagie et al.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * ITEXT GROUP. ITEXT GROUP DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, see http://www.gnu.org/licenses or write to
 * the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA, 02110-1301 USA, or download the license from the following URL:
 * http://itextpdf.com/terms-of-use/
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * In accordance with Section 7(b) of the GNU Affero General Public License,
 * a covered work must retain the producer line in every PDF that is created
 * or manipulated using iText.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the iText software without
 * disclosing the source code of your own applications.
 * These activities include: offering paid services to customers as an ASP,
 * serving PDFs on the fly in a web application, shipping iText with a closed
 * source product.
 *
 * For more information, please contact iText Software Corp. at this
 * address: sales@itextpdf.com
 */

package com.itextpdf.pdfdebug;

import com.intellij.debugger.engine.JavaValue;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XValue;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ByteValue;
import com.sun.jdi.Value;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Copy PdfDocument instance from debug target VM using object serialization.
 */
abstract class CloneRemotePdfDocument {
    private JavaValue valPdfDoc;
    private XDebugSession session;

    CloneRemotePdfDocument(@NotNull final JavaValue pdfDoc, @NotNull final XDebugSession session) {
        this.valPdfDoc = pdfDoc;
        this.session = session;
    }

    final void execute() {
        XDebugProcess dPs = session.getDebugProcess();
        XDebuggerEvaluator eval = dPs.getEvaluator();
        String varName = valPdfDoc.getName();
        String expr = String.format("%s.getSerializedBytes()", varName);
        XSourcePosition currPos = session.getCurrentPosition();
        eval.evaluate(expr, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue result) {
                if(result instanceof JavaValue) {
                    JavaValue jv = (JavaValue)result;
                    Value v = jv.getDescriptor().getValue();
                    if(v==null) {
                        onCloneSuccess(null);
                    } else {
                        ArrayReference bar = (ArrayReference) v;
                        List<Value> bvList = bar.getValues();
                        int baSize = bvList.size();
                        byte[] ba = new byte[baSize];
                        for(int i=0;i<baSize;i++) {
                            ByteValue bv = (ByteValue)bvList.get(i);
                            ba[i] = bv.value();
                        }
                        try {
                            PdfDocument newPdfDoc = PdfDocumentHelper.deserialize(ba);
                            onCloneSuccess(newPdfDoc);
                        } catch (Exception ex) {
                            onCloneError(ex);
                        }
                    }
                } else {
                    onCloneError(new IllegalArgumentException("Unexpected type "+result.getClass()));
                }
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                onCloneError(new RuntimeException(errorMessage));
            }
        }, currPos);
    }

    abstract void onCloneSuccess(PdfDocument newPdfDocument);
    abstract void onCloneError(Throwable t);
}
