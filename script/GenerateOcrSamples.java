import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * 生成 OCR 联调用的样本：有文本层的 PDF、扫描件 PDF（图片页）、带文字的 PNG。
 * 用法：java script/GenerateOcrSamples.java target/e2e
 */
public class GenerateOcrSamples {

    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "target/e2e");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir);
        }
        write(new File(dir, "digital-policy.pdf"), digitalPdf());
        write(new File(dir, "scanned-attendance.pdf"), scannedPdf());
        write(new File(dir, "attendance-notice.png"), pngBytes(noticeImage()));
        System.out.println("samples written to " + dir.getAbsolutePath());
    }

    private static byte[] digitalPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 14);
                stream.newLineAtOffset(60, 720);
                stream.showText("Reimbursement Policy (2026)");
                stream.newLineAtOffset(0, -24);
                stream.setFont(font, 11);
                stream.showText("Invoices must carry the company full name and the tax number.");
                stream.newLineAtOffset(0, -18);
                stream.showText("Tier-1 cities: hotel up to 500 CNY per night.");
                stream.endText();
            }
            return save(document);
        }
    }

    /** 扫描件：页面只有一张位图，没有文本层 —— 只能靠 OCR。 */
    private static byte[] scannedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            BufferedImage image = noticeImage();
            PDPage page = new PDPage();
            document.addPage(page);
            PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(xObject, 60, 520, 480, 240);
            }
            return save(document);
        }
    }

    /** 一张"考勤通知"图片：中文 + 数字，用系统字体渲染。 */
    private static BufferedImage noticeImage() {
        BufferedImage image = new BufferedImage(960, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 960, 480);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Microsoft YaHei", Font.BOLD, 44));
        graphics.drawString("考勤制度通知", 60, 100);
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 32));
        graphics.drawString("忘记打卡每月可申诉 2 次，超过部分按事假处理。", 60, 180);
        graphics.drawString("上班时间 09:30，弹性到 10:00。", 60, 240);
        graphics.drawString("考勤机故障请联系行政部，分机 8888。", 60, 300);
        graphics.dispose();
        return image;
    }

    private static byte[] pngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        document.save(buffer);
        return buffer.toByteArray();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (var out = new java.io.FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
