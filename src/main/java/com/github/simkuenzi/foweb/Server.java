package com.github.simkuenzi.foweb;

import io.javalin.Javalin;
import io.javalin.core.compression.CompressionStrategy;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Server {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("com.github.simkuenzi.http.port", "9000"));
        String context = System.getProperty("com.github.simkuenzi.http.context", "/foweb");
        Path base = Path.of(System.getProperty("user.home"), "foweb");
        new Server(port, context, base).start();
    }

    private final int port;
    private final String context;
    private final Path base;

    public Server(int port, String context, Path base) {
        this.port = port;
        this.context = context;
        this.base = base;
    }

    public void start() {

        Javalin.create(config -> {
            config.contextPath = context;
            // Got those errors on the apache proxy with compression enabled. Related to the Issue below?
            // AH01435: Charset null not supported.  Consider aliasing it?, referer: http://pi/one-egg/
            // AH01436: No usable charset information; using configuration default, referer: http://pi/one-egg/
            config.compressionStrategy(CompressionStrategy.NONE);
        })

        // Workaround for https://github.com/tipsy/javalin/issues/1016
        // Aside from mangled up characters the wrong encoding caused apache proxy to fail on style.css.
        // Apache error log: AH01385: Zlib error -2 flushing zlib output buffer ((null))
        .before(ctx -> {
            if (ctx.res.getCharacterEncoding().equals("utf-8")) {
                ctx.res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            }
        })
        .start(port)

        .get("/", ctx -> ctx.result("curl -d @cv.xml  http://pi/foweb/cv.fo.xslt | ssh pi \"cat > /home/simon/CV/cv.pdf\""))
        .post("/:xslt", ctx -> {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(base.resolve("conf.properties"))) {
                properties.load(in);
            }

            Path xconf = Path.of(properties.getProperty("fopConfig"));
            Path xsltDir = Path.of(properties.getProperty("xsltDir"));
            Path xslt = xsltDir.resolve(ctx.pathParam("xslt"));
            FopFactory fopFactory = FopFactory.newInstance(xconf.toFile());
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdf);
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer(new StreamSource(Files.newInputStream(xslt)));
            Source src = new StreamSource(new ByteArrayInputStream(ctx.bodyAsBytes()));
            Result res = new SAXResult(fop.getDefaultHandler());
            transformer.transform(src, res);
            ctx.contentType(MimeConstants.MIME_PDF).result(new ByteArrayInputStream(pdf.toByteArray()));
        });
    }
}
