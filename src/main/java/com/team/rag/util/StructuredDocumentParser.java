package com.team.rag.util;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import java.io.InputStream;

/** 将 Tika XHTML 的标题、页边界和表格转换为可追踪文本结构，不推测缺失的页码。 */
public final class StructuredDocumentParser extends DefaultHandler {
    private final StringBuilder text = new StringBuilder();
    private boolean cell;
    private int tableDepth;
    private StructuredDocumentParser() {}
    public static String parse(InputStream stream, String filename) throws Exception {
        StructuredDocumentParser handler = new StructuredDocumentParser();
        Metadata metadata = new Metadata();
        metadata.set("resourceName", filename);
        new AutoDetectParser().parse(stream, handler, metadata, new ParseContext());
        return handler.text.toString();
    }
    @Override public void startElement(String uri,String local,String name,Attributes attrs) {
        String tag=local.isEmpty()?name:local;
        if ("div".equals(tag) && "page".equals(attrs.getValue("class"))) text.append("\n\f\n");
        if (tag.matches("h[1-6]")) text.append('\n').append("#".repeat(tag.charAt(1)-'0')).append(' ');
        if ("table".equals(tag)) { tableDepth++; text.append("\n"); }
        if ("tr".equals(tag)) text.append("\n|");
        if ("td".equals(tag)||"th".equals(tag)) cell=true;
    }
    @Override public void characters(char[] chars,int start,int length) {
        String value=new String(chars,start,length);
        text.append(cell?value.replace('\n',' ').replace('|','¦'):value);
    }
    @Override public void endElement(String uri,String local,String name) {
        String tag=local.isEmpty()?name:local;
        if ("td".equals(tag)||"th".equals(tag)) { text.append('|'); cell=false; }
        if ("table".equals(tag)) { tableDepth--; text.append("\n\n"); }
        if ("tr".equals(tag)) text.append('\n');
        if (tag.matches("h[1-6]") || ("p".equals(tag)&&tableDepth==0)) text.append("\n\n");
    }
}
