package com.itextpdf.pdfdebug;

import com.intellij.debugger.engine.JavaValue;
import com.intellij.debugger.ui.DebuggerContentInfo;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.frame.XValueContainer;
import com.intellij.xdebugger.impl.ui.tree.nodes.WatchesRootNode;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.rups.Rups;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.TreeModel;
import java.awt.*;
import java.util.List;

/**
 * @author alangoo
 */
public class AlanAction extends AnAction {
    public static final String TYPE_PDF_DOCUMENT = "com.itextpdf.kernel.pdf.PdfDocument";

    public AlanAction() {
        super("Alan's First Action");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project proj = e.getProject();
        ToolWindowManager wm = ToolWindowManager.getInstance(proj);

        ToolWindow pdfDebugWin = wm.getToolWindow("pdfDebug");
        if(pdfDebugWin==null) {
            pdfDebugWin = wm.registerToolWindow("pdfDebug", true, ToolWindowAnchor.RIGHT);
        }
        ContentFactory cFactory = ContentFactory.SERVICE.getInstance();
        JFrame ideaFrame = WindowManager.getInstance().getFrame(proj);
        JPanel rupsHolder = new JPanel(new BorderLayout());
        Content c = cFactory.createContent(rupsHolder, "Alan Display", false);
        pdfDebugWin.getContentManager().addContent(c);
        pdfDebugWin.activate(null);

        Rups rups = Rups.startNewPlugin(rupsHolder, new Dimension(200, 200), ideaFrame);

        XDebugSession dSess = XDebuggerManager.getInstance(proj).getCurrentSession();
        // determine whether to enable or disable pdfDebug action
        Content varsContent = dSess.getUI().findContent(DebuggerContentInfo.VARIABLES_CONTENT);
        JTree treeComp = (JTree) varsContent.getActionsContextComponent();
        TreeModel tModel = treeComp.getModel();
        WatchesRootNode rootNode = (WatchesRootNode)tModel.getRoot();
        List vars = rootNode.getChildren();
        JavaValue pdfDocVar = findPdfDocument(vars);
        if(pdfDocVar!=null) {
            new CloneRemotePdfDocument(pdfDocVar, dSess) {
                @Override
                void onCloneSuccess(PdfDocument newPdfDocument) {
                    byte[] dbgBytes = PdfDocumentHelper.getDebugBytes(newPdfDocument);
                    rups.loadDocumentFromRawContent(dbgBytes, pdfDocVar.getName(), null, true);
                }

                @Override
                void onCloneError(Throwable t) {
                    System.out.println(t);
                }
            }.execute();
        }
    }

    @Override
    public void update(AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        Project proj = e.getProject();
        XDebugSession dSess = XDebuggerManager.getInstance(proj).getCurrentSession();
        if(dSess==null) {
            presentation.setEnabled(false);
            return;
        }

        // determine whether to enable or disable pdfDebug action
        Content varsContent = dSess.getUI().findContent(DebuggerContentInfo.VARIABLES_CONTENT);
        JTree treeComp = (JTree) varsContent.getActionsContextComponent();
        TreeModel tModel = treeComp.getModel();
        WatchesRootNode rootNode = (WatchesRootNode)tModel.getRoot();
        List vars = rootNode.getChildren();
        JavaValue pdfDocVar = findPdfDocument(vars);

        if(pdfDocVar==null) {
            presentation.setEnabled(false);
        } else {
            presentation.setEnabled(true);
        }
    }

    @Nullable
    private static JavaValue findPdfDocument(List vars) {
        for(Object v : vars) {
            if(v instanceof XValueNodeImpl) {
                XValueContainer vc = ((XValueNodeImpl) v).getValueContainer();
                if(vc instanceof JavaValue) {
                    JavaValue jv = (JavaValue) vc;
                    String type = jv.getDescriptor().getDeclaredType();
                    if(TYPE_PDF_DOCUMENT.equals(type)) return jv;
                }
            }
        }
        return null;
    }
}
