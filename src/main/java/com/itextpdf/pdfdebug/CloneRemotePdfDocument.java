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
 * @author alangoo
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
