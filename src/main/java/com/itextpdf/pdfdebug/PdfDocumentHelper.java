/*
 * $Id$
 *
 * This file is part of the iText (R) project.
 * Copyright (c) 2007-2018 iText Group NV
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

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.rups.model.LoggerHelper;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Method;

/**
 * a utility class handling serialization of PdfDocument instances.
 * @author alangoo
 */
class PdfDocumentHelper {
    public static final String CLASS_TYPE = "com.itextpdf.kernel.pdf.PdfDocument";
    public static final String METHOD_SIGNATURE = "()[B";
    public static final String METHOD_NAME = "getSerializedBytes";

    private static final String NOT_READY_FOR_PLUGIN_MESSAGE = "Cannot get PdfDocument. "
            + "\nMake sure you create reader from stream or string and writer is set to DebugMode.";

    private static final String DOCUMENT_IS_CLOASED_MESSAGE = "The document was closed.";

    private static final String DEBUG_BYTES_METHOD_NAME = "getDebugBytes";
    private static Method getDebugBytesMethod;

    static {
        try {
            getDebugBytesMethod = PdfWriter.class.getDeclaredMethod(DEBUG_BYTES_METHOD_NAME);
            getDebugBytesMethod.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
    }

    private PdfDocumentHelper() {
        // do not create an instance
    }

    static PdfDocument deserialize(byte[] serializedPdfDocument) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serializedPdfDocument));
        PdfDocument newPdfDoc = (PdfDocument) ois.readObject();
        ois.close();
        return newPdfDoc;
    }

    static byte[] getDebugBytes(@NotNull PdfDocument pdfDoc) {
        if (pdfDoc.isClosed()) {
            LoggerHelper.warn(DOCUMENT_IS_CLOASED_MESSAGE, PdfDocumentHelper.class);
            return null;
        }
        PdfWriter writer = pdfDoc.getWriter();
        writer.setCloseStream(true);
        pdfDoc.setCloseWriter(false);
        pdfDoc.close();
        byte[] documentCopyBytes = null;
        try {
            documentCopyBytes = (byte[]) getDebugBytesMethod.invoke(writer);
        } catch (Exception ignored) {
        }
        try {
            writer.close();
        } catch (IOException e) {
            LoggerHelper.error("Writer closing error", e, PdfDocumentHelper.class);
        }
        return documentCopyBytes;
    }
}
