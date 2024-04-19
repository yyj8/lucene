package yyj.demo;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;

public class WildcardQueryDemo {
    private static final String INDEX_DIR = "D:\\workspace\\github\\lucene\\data";

    public static void main(String[] args) throws Exception {
        searchIndex("token", "*00000000000000451cc1f3e314f48943f56d1de917b81a16678618548797335711*");
    }

    private static void searchIndex(String field, String searchText) throws Exception {
        // 打开索引目录
        Directory directory = FSDirectory.open(Paths.get(INDEX_DIR));
        // 创建IndexReader
        try (IndexReader reader = DirectoryReader.open(directory)) {
            // 创建IndexSearcher
            IndexSearcher searcher = new IndexSearcher(reader);
            // 使用标准分析器
            Query query = new WildcardQuery(new Term(field, searchText), 10000000);

            long start = System.currentTimeMillis();
            // 执行搜索
            TopDocs results = searcher.search(query, 10000000);
            long end = System.currentTimeMillis();
            System.out.println("search搜索耗時：" + (end - start));

            // 显示搜索结果
            System.out.println("Total hits: " + results.totalHits);
            for (ScoreDoc scoreDoc : results.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                doc.get(field);
            }
             end = System.currentTimeMillis();

            System.out.println("整体搜索耗時：" + (end - start));
            // 关闭reader
            reader.close();
            directory.close();
        }
    }
}