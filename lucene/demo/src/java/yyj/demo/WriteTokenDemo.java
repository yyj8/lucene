package yyj.demo;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

public class WriteTokenDemo {
    private static final String INDEX_DIR = "D:\\workspace\\github\\lucene\\data";
    private static final String[] WORDS = new String[]{"00000000000000000000000013000001","00000000000000000000000016000001","00000000000000000000000016000002","00000000000000000000000016000003",
            "00000000000000005c4fcccc283a4d7d6e3f0000","000000000000000d808fe7a384ea987b5bb4b9c38a0c3a17011160398 5750060","000000000000000e41044d2144cdb9ea46402de81f624a170472897496128891",
            "000000000000000ee462627c84162baf5379207030e69a169802142653762613","000000000000000v","000000000000003df6c6342994d2fb0fa95b7848598afa166251191918400387",
            "00000000000000451cc1f3e314f48943f56d1de917b81a166786185487973357","0000000000000060bdec0625c473da89cc8924f61dd83a164679235585853782","000000000000006e48f4815de4f2db124f0c890463845a160659964733523258",
            "000000000000006fd16181a95487ba1bd7e585b11f2b8a161988932850011456","0000000000000070ec2cd9de141d087ce25e13a332a74a164945147944057633","000000000000007102d29e0864ea5833d6a1836ff0528a168981588084807693","0000000000000078cc3ec07ce47dea69e022ba8556867a170854003910224020","000000000000007b25fcb2d2b4f60946c323eca761ae1a16409843922 2993030","000000000000007bca3bddedf458e8772bc2580bcf385a169125860714465020","000000000000007e9e7c630cb455bbd5f79ada17f7885a169231633844407337","000000000000008d25eabf53040919f70eee5d778dd70a169946725804419298","00000000000000940c0c30ce24f4c8eb837f428fdda61a165168365835135702","00000000000000a5c3380ea5248ac90acf83bc4d648b7a168899680526512623","00000000000000a99d2dd339e4d19869f6a39dd2f1bf0a159364438851386238","00000000000000b6d118025fe4ccd942f14050ec1b73aa169246012244353594","00000000000000b9387d5d598 43b1ab7141a8c3cd24c5a162424989310455653","00000000000000c86f8132669423788a18df32a391d9ba159339756077461010","00000000000000cdd0fdac79745cc95aa96835999249ca162523772594201317","00000000000000cf78c5bf56b480fa1237bdcec29d40ea169307682664322811","00000000000000d51546148564e838e413a05d58f1694a162230685942018040","00000000000000da5e1c7e72e4cba8693bf6d49ec2a7da161892345518794564","00000000000000db7b18675f24de9a5cac47faa558e52a162230384342299950","00000000000000dcd1636e460450aba6d1a39993a295ca16896255945686 6090","00000000000000df45bbc773247f68de066feab97b5bda167747190910770169","00000000000000e3982b0ece0459da8fff74f8a9d96aaa168781707377222851","00000000000000e74d26411a241ee90cc8e56191bdd11a157923835461377446","00000000000000ef932f4ccd547e0b36b84dc0df100eea160513391001059090","00000000000000f0c9fe8085240ffa8073705c429112ea164684108629552861","00000000000000f1d90ebc3a5426da9b59042957260eba157269471405110821","00000000000000f2e8808c7164d2ebc42fa7e7592002ba164770250709079613","000000000000010706bdfc49c488ea775b59c2c048608a169375490463699930","000000000000010caec9096ff4d138832d62a2a44bf5da164961510308255060","000000000000010dbeaa95c3441df9c7b4d01405c9663a167147353873176357","0000000000000111f5563121b46c0b44580df7a2d93baa168952617099402010"," 0113001b2c8254b80a84b7354f53488fea164181000883256387","000000000000011b5a2bdbb474f23af9ae9d9b8e48a3ba168317414111433456","0000000000000123443aa84284abe9c8a4553084cb0e9a1690947262782619509","00000000000001322c58a3e7f49728e1b59904c87cb15a167976866310626406","0000000000000138f7b292e1543dab1a264107d1e6211a169816313659283258"};

    public static void main(String[] args) throws Exception {
        createIndex(5000000);
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

            for (int i = 0; i < writeDocCount; i++) {
                // 添加字段，【Field.Store.YES/NO】表示字段原始内容是否存储下来，YES:存储；NO:不存储
                //StringField字段不分词，整个字段内容看做一个整体
                StringField field1 = new StringField("id", "id" + i, Field.Store.YES);
                StringField field2 = new StringField("token", WORDS[i % WORDS.length] + i, Field.Store.YES);

                Document doc = new Document();
                doc.add(field1);
                doc.add(field2);
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