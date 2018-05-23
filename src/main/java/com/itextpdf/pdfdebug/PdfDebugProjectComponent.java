package com.itextpdf.pdfdebug;

import com.intellij.debugger.engine.JavaValue;
import com.intellij.debugger.ui.DebuggerContentInfo;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.frame.XValueContainer;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.rups.Rups;
import com.itextpdf.rups.model.LoggerHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;
import java.awt.*;


public class PdfDebugProjectComponent implements ProjectComponent {
    private static final String NOT_READY_FOR_PLUGIN_MESSAGE = "Cannot get PdfDocument. "
            + "\nMake sure you create reader from stream or string and writer is set to DebugMode.";
    public static final String TYPE_PDF_DOCUMENT = "com.itextpdf.kernel.pdf.PdfDocument";
    public static final String WIN_ID_PDFDEBUG = "pdfDebug";
    private Project project;
    private MessageBusConnection busConn;
    private volatile TreeSelectionListener variableSelectionListener;
    private volatile Rups rups;
    private volatile PdfDocument prevDoc;
    private JTree variablesTree;

    public PdfDebugProjectComponent(@NotNull Project proj) {
        this.project = proj;
    }

    /**
     * check if <code>vc</code> is referring to PdfDocument instance.
     * @param vc <code>XValueContainer</code> instance from Variables JTree.
     * @return JavaValue object if it is or null.
     */
    @Nullable
    static JavaValue extractPdfDocument(XValueContainer vc) {
        if(vc instanceof JavaValue) {
            JavaValue jv = (JavaValue) vc;
            String type = jv.getDescriptor().getDeclaredType();
            if(TYPE_PDF_DOCUMENT.equals(type)) return jv;
        }
        return null;
    }

    @Override
    public void projectOpened() {
        busConn = project.getMessageBus().connect();
        busConn.subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
            @Override
            public void processStarted(@NotNull XDebugProcess debugProcess) {
                XDebugSession sess = debugProcess.getSession();

                sess.addSessionListener(new XDebugSessionListener() {
                    @Override
                    public void sessionPaused() {
                        Content varsContent = sess.getUI().findContent(DebuggerContentInfo.VARIABLES_CONTENT);
                        variablesTree = (JTree) varsContent.getActionsContextComponent();
                        if(variableSelectionListener==null) {
                            variableSelectionListener = new TreeSelectionListener() {
                                @Override
                                public void valueChanged(TreeSelectionEvent e) {
                                    if(variablesTree.isValid()) {
                                        updateRupsContent();
                                    } else {
                                        System.out.println("Ignoring transitional event");
                                    }
                                }
                            };
                            variablesTree.addTreeSelectionListener(variableSelectionListener);
                        }

                        updateRupsContent();
                    }
                });
            }

            @Override
            public void processStopped(@NotNull XDebugProcess debugProcess) {
                if(variableSelectionListener!=null) {
                    variablesTree.removeTreeSelectionListener(variableSelectionListener);
                }
                disposePdfWindow();
            }
        });
    }

    private void updateRupsContent() {
        TreePath path = variablesTree.getSelectionPath();
        if(path==null) {
            disposePdfWindow();
        } else {
            Object obj = path.getLastPathComponent();
            if(obj instanceof XValueNodeImpl) {
                XValueContainer vc = ((XValueNodeImpl) obj).getValueContainer();
                JavaValue pdfJv = extractPdfDocument(vc);
                if(pdfJv!=null) {
                    showPdfWindow(pdfJv);
                } else {
                    disposePdfWindow();
                }
            }
        }
    }

    private void showPdfWindow(@NotNull JavaValue pdfDocVar) {
        ToolWindowManager wm = ToolWindowManager.getInstance(project);
        ContentFactory cFactory = ContentFactory.SERVICE.getInstance();
        JFrame ideaFrame = WindowManager.getInstance().getFrame(project);

        ToolWindow pdfDebugWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
        if(pdfDebugWin==null) {
            pdfDebugWin = wm.registerToolWindow("pdfDebug", false, ToolWindowAnchor.RIGHT);
        }

        String name = pdfDocVar.getName();
        Content content = pdfDebugWin.getContentManager().findContent(name);
        if(content==null) {
            JPanel rupsHolder = new JPanel(new BorderLayout());
            content = cFactory.createContent(rupsHolder, name, true);
            pdfDebugWin.getContentManager().addContent(content);
        }
        JComponent holderComp = content.getComponent();

        Runnable afterActivateRunner = new Runnable() {
            @Override
            public void run() {
                Dimension holderSize = holderComp.getSize();
                if(rups==null) {
                    rups = Rups.startNewPlugin(holderComp, holderSize, ideaFrame);
                }
                XDebugSession dSess = XDebuggerManager.getInstance(project).getCurrentSession();
                new CloneRemotePdfDocument(pdfDocVar, dSess) {
                    @Override
                    void onCloneSuccess(PdfDocument newPdfDocument) {
                        if(newPdfDocument==null) {
                            LoggerHelper.error("잘 좀 해봐", PdfDebugProjectComponent.class);
                        } else {
                            boolean isEqual = rups.compareWithDocument(newPdfDocument, true);
                            if(!isEqual) {
                                byte[] dbgBytes = PdfDocumentHelper.getDebugBytes(newPdfDocument);
                                rups.loadDocumentFromRawContent(dbgBytes, name, null, true);
                                rups.highlightLastSavedChanges();
                            }
                        }
                    }

                    @Override
                    void onCloneError(Throwable t) {
                        System.out.println(t);
                    }
                }.execute();
            }
        };

        ToolWindow finalPdfDebugWin = pdfDebugWin;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ToolWindowManager wm = ToolWindowManager.getInstance(project);
                ToolWindow pdfWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
                if(pdfWin!=null) {
                    finalPdfDebugWin.activate(afterActivateRunner);
                }
            }
        });
    }

    private void disposePdfWindow() {
        this.rups = null;
        SwingUtilities.invokeLater(() -> {
            ToolWindowManager wm = ToolWindowManager.getInstance(project);
            ToolWindow pdfWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
            if(pdfWin!=null) {
                wm.unregisterToolWindow(WIN_ID_PDFDEBUG);
            }
            prevDoc = null;
        });
    }

    @Override
    public void projectClosed() {
        busConn.disconnect();
        disposePdfWindow();
    }
}
