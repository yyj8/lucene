/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.MultiLevelSkipListWriter;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.PostingsReaderBase;
import org.apache.lucene.codecs.PostingsWriterBase;
import org.apache.lucene.codecs.lucene90.blocktree.Lucene90BlockTreeTermsReader;
import org.apache.lucene.codecs.lucene90.blocktree.Lucene90BlockTreeTermsWriter;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.TermState;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.packed.PackedInts;

/**
 * Lucene 5.0 postings format, which encodes postings in packed integer blocks for fast decode.
 * Lucene 5.0 发布格式，它将发布内容编码为打包的整数块以便快速解码。
 *
 * <p>Basic idea:基本思路：
 *
 * <ul>
 *   <li><b>Packed Blocks and VInt Blocks</b>:Packed块和VInt块介绍
 *       <p>In packed blocks, integers are encoded with the same bit width ({@link PackedInts packed
 *       format}): the block size (i.e. number of integers inside block) is fixed (currently 128).
 *       Additionally blocks that are all the same value are encoded in an optimized way.
 *       在压缩块中，整数以相同的位宽进行编码，这个块的大小是固定的128位。另外具有相同值的块会以一种优化的方式进行编码；
 *
 *       <p>In VInt blocks, integers are encoded as {@link DataOutput#writeVInt VInt}: the block
 *       size is variable.
 *       在VInt块中，整数被编码为VInt，这个块大小是可变的
 *
 *   <li><b>Block structure</b>:块结构介绍
 *       <p>When the postings are long enough, Lucene90PostingsFormat will try to encode most
 *       integer data as a packed block.
 *       当倒排表足够长时，Lucene90PostingsFormat将尝试将大多数整数数据编码为压缩块。
 *
 *       <p>Take a term with 259 documents as an example, the first 256 document ids are encoded as
 *       two packed blocks, while the remaining 3 are encoded as one VInt block.
 *       以一个包含259个文档的term为例，前256个文档ID会被编码成2个packed块，剩下的3个ID会编码成1个VInt块；
 *
 *       <p>Different kinds of data are always encoded separately into different packed blocks, but
 *       may possibly be interleaved into the same VInt block.
 *       不同类型的数据总是被独立的编码到不同的 packed块中，但是也可能被交叉编码到VInt块中。
 *
 *       <p>This strategy is applied to pairs: &lt;document number, frequency&gt;, &lt;position,
 *       payload length&gt;, &lt;position, offset start, offset length&gt;, and &lt;position,
 *       payload length, offsetstart, offset length&gt;.
 *       这个策略是被应用到下面这些数据对：
 *       *       <document number, frequency>, <position, payload length>, <position, offset start, offset length>,
 *       *       <position, payload length, offsetstart, offset length>
 *
 *   <li><b>Skipdata settings</b>:跳过数据设置
 *       <p>The structure of skip table is quite similar to previous version of Lucene. Skip
 *       interval is the same as block size, and each skip entry points to the beginning of each
 *       block. However, for the first block, skip data is omitted.
 *       跳表的结构与Lucene的前一版本非常相似。 跳表的间隔与块大小相同，每个跳表的entry指向每个块的开始位置。然而，对于第一块，跳过数据被省略。
 *
 *   <li><b>Positions, Payloads, and Offsets</b>:
 *       <p>A position is an integer indicating where the term occurs within one document. A payload
 *       is a blob of metadata associated with current position. An offset is a pair of integers
 *       indicating the tokenized start/end offsets for given term in current position: it is
 *       essentially a specialized payload.
 *       position：表示term出现在文档中的位置，这个位置不是字节偏移量而是第几个term；Positions信息通常用于支持短语查询（Phrase Query）和邻近查询（Proximity Query）
 *       payload：表示当前term position的元数据blob
 *       offset：表示当前term在当前position的term在文档中的字节开始与结束偏移量，它在全文搜索高亮显示比较有帮助
 *
 *       <p>When payloads and offsets are not omitted, numPositions==numPayloads==numOffsets
 *       (assuming a null payload contributes one count). As mentioned in block structure, it is
 *       possible to encode these three either combined or separately.
 *       就像上面【Block structure】中提到的一样，position、payload、offset可能被到一起，也可能被单独编码
 *
 *       <p>In all cases, payloads and offsets are stored together. When encoded as a packed block,
 *       position data is separated out as .pos, while payloads and offsets are encoded in .pay
 *       (payload metadata will also be stored directly in .pay). When encoded as VInt blocks, all
 *       these three are stored interleaved into the .pos (so is payload metadata).
 *       所有情况下，payload和offset都是被存储在一起的。
 *       当编码成packed块时，position数据是被独立存储到 .pos后缀文件中，而payload和offset是被存储到 .pay后缀文件中（payload的元数据也被存储在 .pay文件中）
 *       当编码成VInt块时，position、payload、offset都是被存储到 .pos后缀文件中(包括payload的元数据)
 *
 *       <p>With this strategy, the majority of payload and offset data will be outside .pos file.
 *       So for queries that require only position data, running on a full index with payloads and
 *       offsets, this reduces disk pre-fetches.
 *       使用此策略，大部分payload和offset数据将在.pos文件之外。因此，对于只需要位置数据的查询，在具有payload和offset的完整索引上运行，可以减少磁盘预取。
 * </ul>
 *
 * <p>Files and detailed format:包含的文件和详细个数说明
 *
 * <ul>
 *   <li><code>.tim</code>: <a href="#Termdictionary">Term Dictionary</a>term词典
 *   <li><code>.tip</code>: <a href="#Termindex">Term Index</a>term索引 FST数据结构
 *   <li><code>.doc</code>: <a href="#Frequencies">Frequencies and Skip Data</a>包括文档ID、词频、文档频率、跳表
 *   <li><code>.pos</code>: <a href="#Positions">Positions</a>存放position、(payload、offset这两个不一定会有，可能单独放.pay中)信息
 *   <li><code>.pay</code>: <a href="#Payloads">Payloads and Offsets</a>
 * </ul>
 *
 * <a id="Termdictionary"></a>
 *
 * <dl>【注意：下面有涉及FP的，FP的含义是：文件指针】
 *   <dd><b>Term Dictionary</b>
 *       <p>The .tim file contains the list of terms in each field along with per-term statistics
 *       (such as docfreq) and pointers to the frequencies, positions, payload and skip data in the
 *       .doc, .pos, and .pay files. See {@link Lucene90BlockTreeTermsWriter} for more details on
 *       the format.
 *       .tim文件包含每个字段中的term列表，以及每个term的统计信息（如docfreq）和指向.doc、.pos和.pay文件中的频率、位置、payload信息和跳过数据的指针。
 *       具体个数参考：Lucene90BlockTreeTermsWriter这个类。
 *
 *       <p>NOTE: The term dictionary can plug into different postings implementations: the postings
 *       writer/reader are actually responsible for encoding and decoding the PostingsHeader and
 *       TermMetadata sections described here:
 *       这term词典可以插入不同的倒排实现中：这倒排writer和reader实际上负责编码和解码此处描述的PostingsHeader和TermMetadata部分：
 *       <ul>
 *         <li>PostingsHeader --&gt; Header, PackedBlockSize
 *         <li>TermMetadata --&gt; (DocFPDelta|SingletonDocID), PosFPDelta?, PosVIntBlockFPDelta?,
 *             PayFPDelta?, SkipFPDelta?
 *         <li>Header, --&gt; {@link CodecUtil#writeIndexHeader IndexHeader}
 *         <li>PackedBlockSize, SingletonDocID --&gt; {@link DataOutput#writeVInt VInt}
 *         <li>DocFPDelta, PosFPDelta, PayFPDelta, PosVIntBlockFPDelta, SkipFPDelta --&gt; {@link
 *             DataOutput#writeVLong VLong}
 *         <li>Footer --&gt; {@link CodecUtil#writeFooter CodecFooter}
 *       </ul>
 *       <p>Notes:下面是每个数据结构详细说明
 *       <ul>
 *         <li>Header is a {@link CodecUtil#writeIndexHeader IndexHeader} storing the version
 *             information for the postings.
 *             Header中存储的是倒排的版本信息
 *         <li>PackedBlockSize is the fixed block size for packed blocks. In packed block, bit width
 *             is determined by the largest integer. Smaller block size result in smaller variance
 *             among width of integers hence smaller indexes. Larger block size result in more
 *             efficient bulk i/o hence better acceleration. This value should always be a multiple
 *             of 64, currently fixed as 128 as a tradeoff. It is also the skip interval used to
 *             accelerate {@link org.apache.lucene.index.PostingsEnum#advance(int)}.
 *             PackedBlockSize是压缩块的固定块大小。在压缩块中，位宽由最大整数决定。
 *             较小的块大小导致整数宽度之间的方差较小，因此索引较小。块大小越大，批量i/o效率越高，因此加速效果越好。
 *             这个值应该始终是64的倍数，作为折衷，当前固定为128。它也是用于加速{@link.org.apache.locene.index.PostingsEnum#advanced（int）}的跳过间隔
 *         <li>DocFPDelta determines the position of this term's TermFreqs within the .doc file. In
 *             particular, it is the difference of file offset between this term's data and previous
 *             term's data (or zero, for the first term in the block).On disk it is stored as the
 *             difference from previous value in sequence.
 *             DocFPDelta确定该term的TermFreqs在.doc文件中的位置。
 *             特别地，它是这个term的数据和上一个term的数据之间的文件偏移量的差（或者零，对于块中的第一个term）。在磁盘上，它按顺序存储为与以前值的差值。
 *         <li>PosFPDelta determines the position of this term's TermPositions within the .pos file.
 *             While PayFPDelta determines the position of this term's &lt;TermPayloads,
 *             TermOffsets?&gt; within the .pay file. Similar to DocFPDelta, it is the difference
 *             between two file positions (or neglected, for fields that omit payloads and offsets).
 *             PosFPDelta决定了term在.pos文件中的位置。PayFPDelta决定了term的payload与offset在.pay文件中的位置。
 *             与DocFPDelta类似，它是两个文件位置之间的差异（或者忽略，对于省略payload和offset的字段）
 *         <li>PosVIntBlockFPDelta determines the position of this term's last TermPosition in last
 *             pos packed block within the .pos file. It is synonym for PayVIntBlockFPDelta or
 *             OffsetVIntBlockFPDelta. This is actually used to indicate whether it is necessary to
 *             load following payloads and offsets from .pos instead of .pay. Every time a new block
 *             of positions are to be loaded, the PostingsReader will use this value to check
 *             whether current block is packed format or VInt. When packed format, payloads and
 *             offsets are fetched from .pay, otherwise from .pos. (this value is neglected when
 *             total number of positions i.e. totalTermFreq is less or equal to PackedBlockSize).
 *             PosVIntBlockFPDelta确定该term的最后一个TermPosition在.pos文件中最后一个pos压缩块中的位置。它是PayVIntBlockFPDelta或OffsetVIntBlockFPDelta的同义词。
 *             这实际上用于指示是否有必要从.pos而不是.pay加载payload和offset。每次加载新的位置块时，PostingsReader都会使用此值来检查当前块是压缩格式还是VInt。
 *             当采用压缩格式时，payload和offset从.pay获取，否则从.pos获取（当位置总数，即totalTermFreq小于或等于PackedBlockSize时，忽略此值）。
 *         <li>SkipFPDelta determines the position of this term's SkipData within the .doc file. In
 *             particular, it is the length of the TermFreq data. SkipDelta is only stored if
 *             DocFreq is not smaller than SkipMinimum (i.e. 128 in Lucene90PostingsFormat).
 *             SkipFPDelta决定了term在.poc文件中的SkipData位置。通常，它是TermFreq数据的长度。SkipDelta仅仅在DocFreq不小于SkipMinimum时才存储，这里的SkipMinimum是128位bit
 *         <li>SingletonDocID is an optimization when a term only appears in one document. In this
 *             case, instead of writing a file pointer to the .doc file (DocFPDelta), and then a
 *             VIntBlock at that location, the single document ID is written to the term dictionary.
 *             SingletonDocID是针对term只出现在一个文档中时的优化。在这种情况下，不是将文件指针写入.doc文件（DocFPDelta），然后在该位置写入VIntBlock，而是将单个文档ID写入term字典。
 *       </ul>
 * </dl>
 *
 * <a id="Termindex"></a>
 *
 * <dl>
 *   <dd><b>Term Index</b>term索引
 *       <p>The .tip file contains an index into the term dictionary, so that it can be accessed
 *       randomly. See {@link Lucene90BlockTreeTermsWriter} for more details on the format.
 *       .tip文件包含了到term词典 .tim文件的索引，方便随机访问.tim文件。
 * </dl>
 *
 * <a id="Frequencies"></a>
 *
 * <dl>
 *   <dd><b>Frequencies and Skip Data</b>
 *       <p>The .doc file contains the lists of documents which contain each term, along with the
 *       frequency of the term in that document (except when frequencies are omitted: {@link
 *       IndexOptions#DOCS}). It also saves skip data to the beginning of each packed or VInt block,
 *       when the length of document list is larger than packed block size.
 *       .doc文件包含每个term的文档ID列表以及term在文档中出现次数（除非词频被忽略，也就是IndexOptions被设置为DOCS）
 *       当文档列表的长度大于压缩块的大小时，它还将跳过数据信息保存到每个压缩块或VInt块的开头。
 *       <ul>
 *         <li>docFile(.doc) --&gt; Header, &lt;TermFreqs, SkipData?&gt;<sup>TermCount</sup>, Footer
 *         <li>Header --&gt; {@link CodecUtil#writeIndexHeader IndexHeader}
 *         <li>TermFreqs --&gt; &lt;PackedBlock&gt; <sup>PackedDocBlockNum</sup>, VIntBlock?
 *         <li>PackedBlock --&gt; PackedDocDeltaBlock, PackedFreqBlock?
 *         <li>VIntBlock --&gt; &lt;DocDelta[,
 *             Freq?]&gt;<sup>DocFreq-PackedBlockSize*PackedDocBlockNum</sup>
 *         <li>SkipData --&gt; &lt;&lt;SkipLevelLength, SkipLevel&gt; <sup>NumSkipLevels-1</sup>,
 *             SkipLevel&gt;, SkipDatum?
 *         <li>SkipLevel --&gt; &lt;SkipDatum&gt; <sup>TrimmedDocFreq/(PackedBlockSize^(Level +
 *             1))</sup>
 *         <li>SkipDatum --&gt; DocSkip, DocFPSkip, &lt;PosFPSkip, PosBlockOffset, PayLength?,
 *             PayFPSkip?&gt;?, ImpactLength, &lt;CompetitiveFreqDelta, CompetitiveNormDelta?&gt;
 *             <sup>ImpactCount</sup>, SkipChildLevelPointer?
 *         <li>PackedDocDeltaBlock, PackedFreqBlock --&gt; {@link PackedInts PackedInts}
 *         <li>DocDelta, Freq, DocSkip, DocFPSkip, PosFPSkip, PosBlockOffset, PayByteUpto,
 *             PayFPSkip, ImpactLength, CompetitiveFreqDelta --&gt; {@link DataOutput#writeVInt
 *             VInt}
 *         <li>CompetitiveNormDelta --&gt; {@link DataOutput#writeZLong ZLong}
 *         <li>SkipChildLevelPointer --&gt; {@link DataOutput#writeVLong VLong}
 *         <li>Footer --&gt; {@link CodecUtil#writeFooter CodecFooter}
 *       </ul>
 *       <p>Notes:
 *       <ul>
 *         <li>PackedDocDeltaBlock is theoretically generated from two steps:
 *             <ol>
 *               <li>Calculate the difference between each document number and previous one, and get
 *                   a d-gaps list (for the first document, use absolute value);
 *                   计算每个文档编号与前一个文档编号之间的差异，得到d间隙列表（对于第一个文档，使用绝对值）；
 *               <li>For those d-gaps from first one to
 *                   PackedDocBlockNum*PackedBlockSize<sup>th</sup>, separately encode as packed
 *                   blocks.
 *             </ol>
 *             If frequencies are not omitted, PackedFreqBlock will be generated without d-gap step.
 *             如果不忽略频率，将在没有间隙步长的情况下生成PackedFreqBlock。
 *         <li>VIntBlock stores remaining d-gaps (along with frequencies when possible) with a
 *             format that encodes DocDelta and Freq:
 *             <p>DocDelta: if frequencies are indexed, this determines both the document number and
 *             the frequency. In particular, DocDelta/2 is the difference between this document
 *             number and the previous document number (or zero when this is the first document in a
 *             TermFreqs). When DocDelta is odd, the frequency is one. When DocDelta is even, the
 *             frequency is read as another VInt. If frequencies are omitted, DocDelta contains the
 *             gap (not multiplied by 2) between document numbers and no frequency information is
 *             stored.
 *             <p>For example, the TermFreqs for a term which occurs once in document seven and
 *             three times in document eleven, with frequencies indexed, would be the following
 *             sequence of VInts:
 *             <p>15, 8, 3
 *             <p>If frequencies were omitted ({@link IndexOptions#DOCS}) it would be this sequence
 *             of VInts instead:
 *             <p>7,4
 *         <li>PackedDocBlockNum is the number of packed blocks for current term's docids or
 *             frequencies. In particular, PackedDocBlockNum = floor(DocFreq/PackedBlockSize)
 *         <li>TrimmedDocFreq = DocFreq % PackedBlockSize == 0 ? DocFreq - 1 : DocFreq. We use this
 *             trick since the definition of skip entry is a little different from base interface.
 *             In {@link MultiLevelSkipListWriter}, skip data is assumed to be saved for
 *             skipInterval<sup>th</sup>, 2*skipInterval<sup>th</sup> ... posting in the list.
 *             However, in Lucene90PostingsFormat, the skip data is saved for
 *             skipInterval+1<sup>th</sup>, 2*skipInterval+1<sup>th</sup> ... posting
 *             (skipInterval==PackedBlockSize in this case). When DocFreq is multiple of
 *             PackedBlockSize, MultiLevelSkipListWriter will expect one more skip data than
 *             Lucene90SkipWriter.
 *         <li>SkipDatum is the metadata of one skip entry. For the first block (no matter packed or
 *             VInt), it is omitted.
 *         <li>DocSkip records the document number of every PackedBlockSize<sup>th</sup> document
 *             number in the postings (i.e. last document number in each packed block). On disk it
 *             is stored as the difference from previous value in the sequence.
 *         <li>DocFPSkip records the file offsets of each block (excluding )posting at
 *             PackedBlockSize+1<sup>th</sup>, 2*PackedBlockSize+1<sup>th</sup> ... , in DocFile.
 *             The file offsets are relative to the start of current term's TermFreqs. On disk it is
 *             also stored as the difference from previous SkipDatum in the sequence.
 *         <li>Since positions and payloads are also block encoded, the skip should skip to related
 *             block first, then fetch the values according to in-block offset. PosFPSkip and
 *             PayFPSkip record the file offsets of related block in .pos and .pay, respectively.
 *             While PosBlockOffset indicates which value to fetch inside the related block
 *             (PayBlockOffset is unnecessary since it is always equal to PosBlockOffset). Same as
 *             DocFPSkip, the file offsets are relative to the start of current term's TermFreqs,
 *             and stored as a difference sequence.
 *         <li>PayByteUpto indicates the start offset of the current payload. It is equivalent to
 *             the sum of the payload lengths in the current block up to PosBlockOffset
 *         <li>ImpactLength is the total length of CompetitiveFreqDelta and CompetitiveNormDelta
 *             pairs. CompetitiveFreqDelta and CompetitiveNormDelta are used to safely skip score
 *             calculation for uncompetitive documents; See {@link
 *             org.apache.lucene.codecs.CompetitiveImpactAccumulator} for more details.
 *       </ul>
 * </dl>
 *
 * <a id="Positions"></a>
 *
 * <dl>
 *   <dd><b>Positions</b>
 *       <p>The .pos file contains the lists of positions that each term occurs at within documents.
 *       It also sometimes stores part of payloads and offsets for speedup.
 *       <ul>
 *         <li>PosFile(.pos) --&gt; Header, &lt;TermPositions&gt; <sup>TermCount</sup>, Footer
 *         <li>Header --&gt; {@link CodecUtil#writeIndexHeader IndexHeader}
 *         <li>TermPositions --&gt; &lt;PackedPosDeltaBlock&gt; <sup>PackedPosBlockNum</sup>,
 *             VIntBlock?
 *         <li>VIntBlock --&gt; &lt;PositionDelta[, PayloadLength?], PayloadData?, OffsetDelta?,
 *             OffsetLength?&gt;<sup>PosVIntCount</sup>
 *         <li>PackedPosDeltaBlock --&gt; {@link PackedInts PackedInts}
 *         <li>PositionDelta, OffsetDelta, OffsetLength --&gt; {@link DataOutput#writeVInt VInt}
 *         <li>PayloadData --&gt; {@link DataOutput#writeByte byte}<sup>PayLength</sup>
 *         <li>Footer --&gt; {@link CodecUtil#writeFooter CodecFooter}
 *       </ul>
 *       <p>Notes:
 *       <ul>
 *         <li>TermPositions are order by term (terms are implicit, from the term dictionary), and
 *             position values for each term document pair are incremental, and ordered by document
 *             number.
 *         <li>PackedPosBlockNum is the number of packed blocks for current term's positions,
 *             payloads or offsets. In particular, PackedPosBlockNum =
 *             floor(totalTermFreq/PackedBlockSize)
 *         <li>PosVIntCount is the number of positions encoded as VInt format. In particular,
 *             PosVIntCount = totalTermFreq - PackedPosBlockNum*PackedBlockSize
 *         <li>The procedure how PackedPosDeltaBlock is generated is the same as PackedDocDeltaBlock
 *             in chapter <a href="#Frequencies">Frequencies and Skip Data</a>.
 *         <li>PositionDelta is, if payloads are disabled for the term's field, the difference
 *             between the position of the current occurrence in the document and the previous
 *             occurrence (or zero, if this is the first occurrence in this document). If payloads
 *             are enabled for the term's field, then PositionDelta/2 is the difference between the
 *             current and the previous position. If payloads are enabled and PositionDelta is odd,
 *             then PayloadLength is stored, indicating the length of the payload at the current
 *             term position.
 *         <li>For example, the TermPositions for a term which occurs as the fourth term in one
 *             document, and as the fifth and ninth term in a subsequent document, would be the
 *             following sequence of VInts (payloads disabled):
 *             <p>4, 5, 4
 *         <li>PayloadData is metadata associated with the current term position. If PayloadLength
 *             is stored at the current position, then it indicates the length of this payload. If
 *             PayloadLength is not stored, then this payload has the same length as the payload at
 *             the previous position.
 *         <li>OffsetDelta/2 is the difference between this position's startOffset from the previous
 *             occurrence (or zero, if this is the first occurrence in this document). If
 *             OffsetDelta is odd, then the length (endOffset-startOffset) differs from the previous
 *             occurrence and an OffsetLength follows. Offset data is only written for {@link
 *             IndexOptions#DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS}.
 *       </ul>
 * </dl>
 *
 * <a id="Payloads"></a>
 *
 * <dl>
 *   <dd><b>Payloads and Offsets</b>
 *       <p>The .pay file will store payloads and offsets associated with certain term-document
 *       positions. Some payloads and offsets will be separated out into .pos file, for performance
 *       reasons.
 *       <ul>
 *         <li>PayFile(.pay): --&gt; Header, &lt;TermPayloads?, TermOffsets?&gt;
 *             <sup>TermCount</sup>, Footer
 *         <li>Header --&gt; {@link CodecUtil#writeIndexHeader IndexHeader}
 *         <li>TermPayloads --&gt; &lt;PackedPayLengthBlock, SumPayLength, PayData&gt;
 *             <sup>PackedPayBlockNum</sup>
 *         <li>TermOffsets --&gt; &lt;PackedOffsetStartDeltaBlock, PackedOffsetLengthBlock&gt;
 *             <sup>PackedPayBlockNum</sup>
 *         <li>PackedPayLengthBlock, PackedOffsetStartDeltaBlock, PackedOffsetLengthBlock --&gt;
 *             {@link PackedInts PackedInts}
 *         <li>SumPayLength --&gt; {@link DataOutput#writeVInt VInt}
 *         <li>PayData --&gt; {@link DataOutput#writeByte byte}<sup>SumPayLength</sup>
 *         <li>Footer --&gt; {@link CodecUtil#writeFooter CodecFooter}
 *       </ul>
 *       <p>Notes:
 *       <ul>
 *         <li>The order of TermPayloads/TermOffsets will be the same as TermPositions, note that
 *             part of payload/offsets are stored in .pos.
 *         <li>The procedure how PackedPayLengthBlock and PackedOffsetLengthBlock are generated is
 *             the same as PackedFreqBlock in chapter <a href="#Frequencies">Frequencies and Skip
 *             Data</a>. While PackedStartDeltaBlock follows a same procedure as
 *             PackedDocDeltaBlock.
 *         <li>PackedPayBlockNum is always equal to PackedPosBlockNum, for the same term. It is also
 *             synonym for PackedOffsetBlockNum.
 *         <li>SumPayLength is the total length of payloads written within one block, should be the
 *             sum of PayLengths in one packed block.
 *         <li>PayLength in PackedPayLengthBlock is the length of each payload associated with the
 *             current position.
 *       </ul>
 * </dl>
 *
 * @lucene.experimental
 */
public final class Lucene90PostingsFormat extends PostingsFormat {

  /**
   * Filename extension for document number, frequencies, and skip data. See chapter: <a
   * href="#Frequencies">Frequencies and Skip Data</a>
   */
  public static final String DOC_EXTENSION = "doc";

  /** Filename extension for positions. See chapter: <a href="#Positions">Positions</a> */
  public static final String POS_EXTENSION = "pos";

  /**
   * Filename extension for payloads and offsets. See chapter: <a href="#Payloads">Payloads and
   * Offsets</a>
   */
  public static final String PAY_EXTENSION = "pay";

  /** Size of blocks. */
  public static final int BLOCK_SIZE = ForUtil.BLOCK_SIZE;

  /**
   * Expert: The maximum number of skip levels. Smaller values result in slightly smaller indexes,
   * but slower skipping in big posting lists.
   */
  static final int MAX_SKIP_LEVELS = 10;

  static final String TERMS_CODEC = "Lucene90PostingsWriterTerms";
  static final String DOC_CODEC = "Lucene90PostingsWriterDoc";
  static final String POS_CODEC = "Lucene90PostingsWriterPos";
  static final String PAY_CODEC = "Lucene90PostingsWriterPay";

  // Increment version to change it
  static final int VERSION_START = 0;
  static final int VERSION_CURRENT = VERSION_START;

  private final int minTermBlockSize;
  private final int maxTermBlockSize;

  /** Creates {@code Lucene90PostingsFormat} with default settings. */
  public Lucene90PostingsFormat() {
    this(
        Lucene90BlockTreeTermsWriter.DEFAULT_MIN_BLOCK_SIZE,
        Lucene90BlockTreeTermsWriter.DEFAULT_MAX_BLOCK_SIZE);
  }

  /**
   * Creates {@code Lucene90PostingsFormat} with custom values for {@code minBlockSize} and {@code
   * maxBlockSize} passed to block terms dictionary.
   *
   * @see
   *     Lucene90BlockTreeTermsWriter#Lucene90BlockTreeTermsWriter(SegmentWriteState,PostingsWriterBase,int,int)
   */
  public Lucene90PostingsFormat(int minTermBlockSize, int maxTermBlockSize) {
    super("Lucene90");
    Lucene90BlockTreeTermsWriter.validateSettings(minTermBlockSize, maxTermBlockSize);
    this.minTermBlockSize = minTermBlockSize;
    this.maxTermBlockSize = maxTermBlockSize;
  }

  @Override
  public String toString() {
    return getName();
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    PostingsWriterBase postingsWriter = new Lucene90PostingsWriter(state);
    boolean success = false;
    try {
      FieldsConsumer ret =
          new Lucene90BlockTreeTermsWriter(
              state, postingsWriter, minTermBlockSize, maxTermBlockSize);
      success = true;
      return ret;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(postingsWriter);
      }
    }
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    PostingsReaderBase postingsReader = new Lucene90PostingsReader(state);
    boolean success = false;
    try {
      FieldsProducer ret = new Lucene90BlockTreeTermsReader(postingsReader, state);
      success = true;
      return ret;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(postingsReader);
      }
    }
  }

  /**
   * Holds all state required for {@link Lucene90PostingsReader} to produce a {@link
   * org.apache.lucene.index.PostingsEnum} without re-seeking the terms dict.
   *
   * @lucene.internal
   */
  public static final class IntBlockTermState extends BlockTermState {
    /** file pointer to the start of the doc ids enumeration, in {@link #DOC_EXTENSION} file */
    public long docStartFP;
    /** file pointer to the start of the positions enumeration, in {@link #POS_EXTENSION} file */
    public long posStartFP;
    /** file pointer to the start of the payloads enumeration, in {@link #PAY_EXTENSION} file */
    public long payStartFP;
    /**
     * file offset for the start of the skip list, relative to docStartFP, if there are more than
     * {@link ForUtil#BLOCK_SIZE} docs; otherwise -1
     */
    public long skipOffset;
    /**
     * file offset for the last position in the last block, if there are more than {@link
     * ForUtil#BLOCK_SIZE} positions; otherwise -1
     */
    public long lastPosBlockOffset;
    /**
     * docid when there is a single pulsed posting, otherwise -1. freq is always implicitly
     * totalTermFreq in this case.
     */
    public int singletonDocID;

    /** Sole constructor. */
    public IntBlockTermState() {
      skipOffset = -1;
      lastPosBlockOffset = -1;
      singletonDocID = -1;
    }

    @Override
    public IntBlockTermState clone() {
      IntBlockTermState other = new IntBlockTermState();
      other.copyFrom(this);
      return other;
    }

    @Override
    public void copyFrom(TermState _other) {
      super.copyFrom(_other);
      IntBlockTermState other = (IntBlockTermState) _other;
      docStartFP = other.docStartFP;
      posStartFP = other.posStartFP;
      payStartFP = other.payStartFP;
      lastPosBlockOffset = other.lastPosBlockOffset;
      skipOffset = other.skipOffset;
      singletonDocID = other.singletonDocID;
    }

    @Override
    public String toString() {
      return super.toString()
          + " docStartFP="
          + docStartFP
          + " posStartFP="
          + posStartFP
          + " payStartFP="
          + payStartFP
          + " lastPosBlockOffset="
          + lastPosBlockOffset
          + " singletonDocID="
          + singletonDocID;
    }
  }
}
