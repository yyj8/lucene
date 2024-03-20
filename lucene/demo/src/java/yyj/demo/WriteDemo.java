package yyj.demo;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;

public class WriteDemo {
    private static final String INDEX_DIR = "D:\\workspace\\github\\lucene\\data";

    public static void main(String[] args) throws Exception {
        createIndex(10);
    }

    private static void createIndex(int writeDocCount) throws IOException {
        // 指定索引存储的位置
        Directory directory = FSDirectory.open(Paths.get(INDEX_DIR));
        // 使用标准分析器，如果有引入其他的分词器，这里可以使用其他分词器实现，比如IK分词器
        Analyzer analyzer = new StandardAnalyzer();
        // 配置IndexWriter
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        //设置为非复合存储索引，这行如果不设置，会被打包到.cfs、.cfe、.si文件
        config.setUseCompoundFile(false);
        // 创建IndexWriter
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            // 创建一个FieldType实例
            FieldType fieldType = new FieldType();
            // 设置索引选项，例如记录文档编号和词项频率
            //https://www.elastic.co/guide/en/elasticsearch/reference/7.10/index-options.html
            fieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS);

            // 设置其他相关的字段属性
            fieldType.setStored(true); // 设置字段值是否存储
            fieldType.setTokenized(true); // 设置字段是否分词
            //设置是否归一化处理，true忽略归一化处理，false进行归一化处理；false时会生成 .nvd和.nvm文件
            fieldType.setOmitNorms(false);

            for (int i = 0; i < writeDocCount; i++) {
                // 添加字段，【Field.Store.YES/NO】表示字段原始内容是否存储下来，YES:存储；NO:不存储
                //StringField字段不分词，整个字段内容看做一个整体
                StringField field1 = new StringField("id", "id" + i, Field.Store.YES);
                //更细粒度的字段属性控制
                Field field2 = new Field("content", "This is an example text for Lucene indexing length is " + i, fieldType);
                Field field3 = new Field("name", "my name is name" + i, fieldType);

                //NumericDocValuesField字段是DocValues字段，用于聚合时用，会生成：
                // (1).dvd后缀文件（这是“DocValues Data”的缩写）；
                // (2).dvm后缀文件（这是“DocValues Metadata”的缩写）
                NumericDocValuesField field4 = new NumericDocValuesField("price", 10 + i);

                Document doc = new Document();
                doc.add(field1);
                doc.add(field2);
                doc.add(field3);
                doc.add(field4);
                writer.addDocument(doc);
            }
            //把所有segment强制合并成一个segment
            writer.forceMerge(1);
            writer.close();
        } finally {
            directory.close();
        }
    }

}