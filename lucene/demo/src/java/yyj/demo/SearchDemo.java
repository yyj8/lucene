package yyj.demo;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;
import java.util.List;

public class SearchDemo {
    private static final String INDEX_DIR = "D:\\workspace\\github\\lucene\\data";

    public static void main(String[] args) throws Exception {
        searchIndex("content", "Lucene");
    }

    private static void searchIndex(String field, String searchText) throws Exception {
        // 打开索引目录
        Directory directory = FSDirectory.open(Paths.get(INDEX_DIR));
        // 创建IndexReader
        try (IndexReader reader = DirectoryReader.open(directory)) {
            // 创建IndexSearcher
            IndexSearcher searcher = new IndexSearcher(reader);
            // 使用标准分析器
            StandardAnalyzer analyzer = new StandardAnalyzer();
            // 创建查询解析器
            QueryParser parser = new QueryParser(field, analyzer);
            // 解析查询字符串
            Query query = parser.parse(searchText);

//            List<LeafReaderContext> leaves = reader.leaves();
//            for(LeafReaderContext leafReaderContext:leaves)
//            {
//                leafReaderContext.reader().getNumericDocValues("price").
//            }

            // 执行搜索
            TopDocs results = searcher.search(query, 10);
            // 遍历搜索结果
            for (ScoreDoc scoreDoc : results.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                List<IndexableField> fields = doc.getFields();
                StringBuilder sb = new StringBuilder();
                sb.append("lucene doc id=" + scoreDoc.doc + ";");
                for (IndexableField f : fields) {
                    sb.append(f.name());
                    sb.append("=");
                    sb.append(f.stringValue());
                    sb.append(";");
                }
                //获取docvalue值，不能这么获取，上面注释部分未写完
                //sb.append("price=" + doc.get("price") + ";");
                System.out.println(sb.toString());
            }
        }
    }
}