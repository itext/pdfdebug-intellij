package com.itextpdf.pdfdebug;

import ch.qos.logback.core.util.StatusPrinter;
import com.intellij.debugger.engine.JavaValue;
import com.intellij.debugger.ui.DebuggerContentInfo;
import com.intellij.openapi.application.ApplicationManager;
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
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreeListener;
import com.intellij.xdebugger.impl.ui.tree.nodes.XDebuggerTreeNode;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.rups.Rups;
import com.itextpdf.rups.model.LoggerHelper;
import icons.PdfIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

public class PdfDebugProjectComponent implements ProjectComponent {
    private static final String NOT_READY_FOR_PLUGIN_MESSAGE = "Cannot get PdfDocument. "
            + "\nMake sure you create reader from stream or string and writer is set to DebugMode.";
    public static final String TYPE_PDF_DOCUMENT = "com.itextpdf.kernel.pdf.PdfDocument";
    public static final String WIN_ID_PDFDEBUG = "pdfDebug";
    private Project project;
    private MessageBusConnection busConn;
    private volatile TreeSelectionListener variableSelectionListener;
    private volatile Rups rups;
    private volatile XDebuggerTree variablesTree;

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
                        // current thread is not EDT
                        // ensuring 'variablesTree' has right reference
                        if(variablesTree==null) {
                            Content varsContent = sess.getUI().findContent(DebuggerContentInfo.VARIABLES_CONTENT);
                            variablesTree = (XDebuggerTree) varsContent.getActionsContextComponent();
                            variableSelectionListener = new TreeSelectionListener() {
                                @Override
                                public void valueChanged(TreeSelectionEvent e) {
                                    if(e.isAddedPath()) {
                                        updateRupsContent();
                                    }
                                }
                            };
                            variablesTree.addTreeSelectionListener(variableSelectionListener);
                            variablesTree.addTreeListener(new XDebuggerTreeListener() {
                                @Override
                                public void childrenLoaded(@NotNull XDebuggerTreeNode node, @NotNull List<XValueContainerNode<?>> children, boolean last) {
                                    // only for root node
                                    if(node.getParent()==null) {
                                        // a dirty hack to get stable selection state
                                        // currently I didn't find better way
                                        Timer nodeRestoreWaiter = new Timer(500, new ActionListener() {
                                            @Override
                                            public void actionPerformed(ActionEvent e) {
                                                updateRupsContent();
                                            }
                                        });
                                        nodeRestoreWaiter.setRepeats(false);
                                        nodeRestoreWaiter.start();
                                    }
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void processStopped(@NotNull XDebugProcess debugProcess) {
                if(variableSelectionListener!=null) {
                    variablesTree.removeTreeSelectionListener(variableSelectionListener);
                    variablesTree = null;
                }
                disposePdfWindow();
            }
        });
    }

    private void updateRupsContent() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if(variablesTree==null) return;

        TreePath path = variablesTree.getSelectionPath();
        if(path==null) {
            disposePdfWindow();
        } else {
            Object obj = path.getLastPathComponent();
            if(obj instanceof XValueNodeImpl) {
                XValueContainer vc = ((XValueNodeImpl) obj).getValueContainer();
                JavaValue pdfJv = extractPdfDocument(vc);
                if(pdfJv!=null) {
                    showPdfWindowEdtOnly(pdfJv);
                } else {
                    disposePdfWindow();
                }
            }
        }
    }

    /**
     * MUST be called on EDT(Event Dispatch Thread)
     * @param pdfDocVar
     */
    private void showPdfWindowEdtOnly(@NotNull JavaValue pdfDocVar) {
        ApplicationManager.getApplication().assertIsDispatchThread();

        ToolWindowManager wm = ToolWindowManager.getInstance(project);
        ContentFactory cFactory = ContentFactory.SERVICE.getInstance();
        JFrame ideaFrame = WindowManager.getInstance().getFrame(project);

        ToolWindow pdfDebugWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
        if(pdfDebugWin==null) {
            pdfDebugWin = wm.registerToolWindow("pdfDebug", false, ToolWindowAnchor.RIGHT);
            pdfDebugWin.setIcon(PdfIcons.ACTION_PDF_DEBUG);
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
                            LoggerHelper.error(NOT_READY_FOR_PLUGIN_MESSAGE, PdfDebugProjectComponent.class);
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

        ToolWindow pdfWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
        if(pdfWin!=null) {
            pdfDebugWin.activate(afterActivateRunner);
        }
    }

    private void disposePdfWindow() {
        if(this.rups==null) return;

        this.rups.clearHighlights();
        this.rups = null;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ToolWindowManager wm = ToolWindowManager.getInstance(project);
                ToolWindow pdfWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
                if(pdfWin!=null) {
                    wm.unregisterToolWindow(WIN_ID_PDFDEBUG);
                }
            }
        });
    }

    @Override
    public void projectClosed() {
        busConn.disconnect();
        disposePdfWindow();
    }
}
