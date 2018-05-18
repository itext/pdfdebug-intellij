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
