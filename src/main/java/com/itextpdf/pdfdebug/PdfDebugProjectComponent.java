package com.itextpdf.pdfdebug;

import com.intellij.debugger.engine.JavaValue;
import com.intellij.debugger.ui.DebuggerContentInfo;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.frame.XValueContainer;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;


public class PdfDebugProjectComponent implements ProjectComponent {
    private Project project;
    private MessageBusConnection busConn;
    private TreeSelectionListener variableSelectionListener;
    private JTree variablesTree;

    public PdfDebugProjectComponent(@NotNull Project proj) {
        this.project = proj;
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
                        if(variableSelectionListener==null) {
                            Content varsContent = sess.getUI().findContent(DebuggerContentInfo.VARIABLES_CONTENT);
                            variablesTree = (JTree) varsContent.getActionsContextComponent();
                            variableSelectionListener = e -> {
                                TreePath path = e.getPath();
                                if(path==null) {
                                    // hide RUPS panel
                                } else {
                                    Object obj = path.getLastPathComponent();
                                    if(obj instanceof XValueNodeImpl) {
                                        XValueContainer vc = ((XValueNodeImpl) obj).getValueContainer();
                                        JavaValue pdfJv = AlanAction.extractPdfDocument(vc);
                                        if(pdfJv!=null) {
                                            System.out.println("Show in RUPS "+pdfJv);
                                        }
                                    }
                                }
                            };
                            variablesTree.addTreeSelectionListener(variableSelectionListener);
                        } else {
                            return;
                        }
                    }
                });
            }

            @Override
            public void processStopped(@NotNull XDebugProcess debugProcess) {
                if(variableSelectionListener!=null) {
                    variablesTree.removeTreeSelectionListener(variableSelectionListener);
                }
            }
        });
        // 1. wait for debug session
    }

    @Override
    public void projectClosed() {
        busConn.disconnect();
    }
}
