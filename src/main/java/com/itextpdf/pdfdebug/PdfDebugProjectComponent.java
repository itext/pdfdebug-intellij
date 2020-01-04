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
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.rups.Rups;
import com.itextpdf.rups.event.RupsEvent;
import com.itextpdf.rups.model.LoggerHelper;
import icons.PdfIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Observable;
import java.util.Observer;

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
                        }
                    }
                });
            }

            @Override
            public void processStopped(@NotNull XDebugProcess debugProcess) {
                if(variableSelectionListener!=null) {
                    if(variablesTree!=null) {
                        variablesTree.removeTreeSelectionListener(variableSelectionListener);
                        variablesTree = null;
                    }
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
                            ByteArrayInputStream bais = null;
                            try {
                                byte[] dbgBytes = PdfDocumentHelper.getDebugBytes(newPdfDocument);
                                bais = new ByteArrayInputStream(dbgBytes);
                                PdfReader reader = new PdfReader(bais);
                                PdfDocument tempDoc = new PdfDocument(reader);

                                boolean isEqual = rups.compareWithDocument(tempDoc, true);
                                if(!isEqual) {
                                    listenOnetimeForHighlight(rups);
                                    rups.loadDocumentFromRawContent(dbgBytes, name, null, true);
                                } else {
                                    rups.highlightLastSavedChanges();
                                }
                            } catch (Exception ex) {
                                LoggerHelper.error("Error while reading pdf file.", ex, PdfDebugProjectComponent.class);
                                ex.printStackTrace();
                            } finally {
                                try {
                                    if(bais!=null) bais.close();
                                } catch (IOException ioex) {
                                    // never happens
                                }
                            }
                        }
                    }

                    @Override
                    void onCloneError(Throwable t) {
                        Exception ex = null;
                        if(t instanceof Exception) {
                            ex = (Exception) t;
                        }
                        LoggerHelper.warn("Failed to reconstruct PdfDocument instance", ex, PdfDebugProjectComponent.class);
                    }
                }.execute();
            }
        };

        ToolWindow pdfWin = wm.getToolWindow(WIN_ID_PDFDEBUG);
        if(pdfWin!=null) {
            pdfDebugWin.activate(afterActivateRunner);
        }
    }

    private static void listenOnetimeForHighlight(Rups rups) {
        final Observer openObserver = new Observer() {
            @Override
            public void update(Observable o, Object arg) {
                if (!(arg instanceof RupsEvent)) return;
                RupsEvent re = (RupsEvent) arg;
                // only cares for OPEN_DOCUMENT_POST_EVENT
                if(re.getType()!=RupsEvent.OPEN_DOCUMENT_POST_EVENT) return;
                Observer listener = this;
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        rups.highlightLastSavedChanges();
                        rups.unregisterEventObserver(listener);
                    }
                });
            }
        };

        rups.registerEventObserver(openObserver);
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
