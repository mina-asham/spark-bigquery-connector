/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.spark.bigquery.integration;

import static com.google.common.truth.Truth.assertThat;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeThat;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Field.Mode;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TimePartitioning;
import com.google.cloud.spark.bigquery.SchemaConverters;
import com.google.cloud.spark.bigquery.SchemaConvertersConfiguration;
import com.google.cloud.spark.bigquery.SparkBigQueryConfig;
import com.google.cloud.spark.bigquery.SparkBigQueryConfig.WriteMethod;
import com.google.cloud.spark.bigquery.integration.model.Data;
import com.google.cloud.spark.bigquery.integration.model.Friend;
import com.google.cloud.spark.bigquery.integration.model.Link;
import com.google.cloud.spark.bigquery.integration.model.Person;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.inject.ProvisionException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.Transformer;
import org.apache.spark.ml.feature.MinMaxScaler;
import org.apache.spark.ml.feature.MinMaxScalerModel;
import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.ml.linalg.SQLDataTypes;
import org.apache.spark.package$;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.MetadataBuilder;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import scala.Some;

abstract class WriteIntegrationTestBase extends SparkBigQueryIntegrationTestBase {

  protected static AtomicInteger id = new AtomicInteger(0);
  protected final SparkBigQueryConfig.WriteMethod writeMethod;
  protected Class<? extends Exception> expectedExceptionOnExistingTable;
  protected BigQuery bq;

  public WriteIntegrationTestBase(SparkBigQueryConfig.WriteMethod writeMethod) {
    this(writeMethod, IllegalArgumentException.class);
  }

  public WriteIntegrationTestBase(
      SparkBigQueryConfig.WriteMethod writeMethod,
      Class<? extends Exception> expectedExceptionOnExistingTable) {
    super();
    this.writeMethod = writeMethod;
    this.expectedExceptionOnExistingTable = expectedExceptionOnExistingTable;
    this.bq = BigQueryOptions.getDefaultInstance().getService();
  }

  private Metadata metadata(Map<String, String> map) {
    MetadataBuilder metadata = new MetadataBuilder();
    map.forEach((key, value) -> metadata.putString(key, value));
    return metadata.build();
  }

  @Before
  public void createTestTableName() {
    // have a fresh table for each test
    this.testTable = "test_" + System.nanoTime();
  }

  private String createDiffInSchemaDestTable(String schema) {
    String destTableName = TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_NAME + "_" + System.nanoTime();
    IntegrationTestUtils.runQuery(String.format(schema, testDataset, destTableName));
    return destTableName;
  }

  // Write tests. We have four save modes: Append, ErrorIfExists, Ignore and
  // Overwrite. For each there are two behaviours - the table exists or not.
  // See more at http://spark.apache.org/docs/2.3.2/api/java/org/apache/spark/sql/SaveMode.html

  protected Dataset<Row> initialData() {
    return spark
        .createDataset(
            Arrays.asList(
                new Person(
                    "Abc",
                    Arrays.asList(new Friend(10, Arrays.asList(new Link("www.abc.com"))))), //
                new Person(
                    "Def", Arrays.asList(new Friend(12, Arrays.asList(new Link("www.def.com")))))),
            Encoders.bean(Person.class))
        .toDF();
  }

  protected Dataset<Row> additonalData() {
    return spark
        .createDataset(
            Arrays.asList(
                new Person(
                    "Xyz", Arrays.asList(new Friend(10, Arrays.asList(new Link("www.xyz.com"))))),
                new Person(
                    "Pqr", Arrays.asList(new Friend(12, Arrays.asList(new Link("www.pqr.com")))))),
            Encoders.bean(Person.class))
        .toDF();
  }

  protected int testTableNumberOfRows() throws InterruptedException {
    return testTableNumberOfRows(testTable);
  }

  protected int testTableNumberOfRows(String table) throws InterruptedException {
    String query = String.format("select * from %s.%s", testDataset.toString(), table);
    return (int) bq.query(QueryJobConfiguration.of(query)).getTotalRows();
  }

  private StandardTableDefinition testPartitionedTableDefinition() {
    return bq.getTable(testDataset.toString(), testTable + "_partitioned").getDefinition();
  }

  protected void writeToBigQueryAvroFormat(
      Dataset<Row> df, SaveMode mode, String writeAtLeastOnce) {
    writeToBigQuery(df, mode, "avro", writeAtLeastOnce);
  }

  protected void writeToBigQuery(Dataset<Row> df, SaveMode mode, String format) {
    writeToBigQuery(df, mode, format, "False");
  }

  protected void writeToBigQuery(
      Dataset<Row> df, SaveMode mode, String format, String writeAtLeastOnce) {
    df.write()
        .format("bigquery")
        .mode(mode)
        .option("table", fullTableName())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", format)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .save();
  }

  Dataset<Row> readAllTypesTable() {
    return spark
        .read()
        .format("bigquery")
        .option("dataset", testDataset.toString())
        .option("table", TestConstants.ALL_TYPES_TABLE_NAME)
        .load();
  }

  private void writeToBigQuery_AppendSaveMode_Internal(String writeAtLeastOnce)
      throws InterruptedException {
    // initial write
    writeToBigQueryAvroFormat(initialData(), SaveMode.Append, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
    // second write
    writeToBigQueryAvroFormat(additonalData(), SaveMode.Append, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(4);
    assertThat(additionalDataValuesExist()).isTrue();
  }

  @Test
  public void testWriteToBigQuery_AppendSaveMode() throws InterruptedException {
    writeToBigQuery_AppendSaveMode_Internal("False");
  }

  @Test
  public void testWriteToBigQuery_AppendSaveMode_AtLeastOnce() throws InterruptedException {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuery_AppendSaveMode_Internal("True");
  }

  private void writeToBigQuery_WithTableLabels_Internal(String writeAtLeastOnce) {
    Dataset<Row> df = initialData();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("table", fullTableName())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", "avro")
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .option("bigQueryTableLabel.alice", "bob")
        .option("bigQueryTableLabel.foo", "bar")
        .save();

    Table bigQueryTable = bq.getTable(testDataset.toString(), testTable);
    Map<String, String> tableLabels = bigQueryTable.getLabels();
    assertEquals(2, tableLabels.size());
    assertEquals("bob", tableLabels.get("alice"));
    assertEquals("bar", tableLabels.get("foo"));
  }

  @Test
  public void testWriteToBigQuery_WithTableLabels() {
    writeToBigQuery_WithTableLabels_Internal("False");
  }

  @Test
  public void testWriteToBigQuery_WithTableLabels_AtLeastOnce() {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuery_WithTableLabels_Internal("True");
  }

  private void writeToBigQuery_EnableListInference_Internal(String writeAtLeastOnce)
      throws InterruptedException {
    Dataset<Row> df = initialData();
    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("table", fullTableName())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", "parquet")
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .option("enableListInference", true)
        .save();

    Dataset<Row> readDF =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .load();
    SchemaConverters sc = SchemaConverters.from(SchemaConvertersConfiguration.of(ZoneId.of("UTC")));
    Schema initialSchema = sc.toBigQuerySchema(df.schema());
    Schema readSchema = sc.toBigQuerySchema(readDF.schema());
    assertEquals(initialSchema, readSchema);
  }

  @Test
  public void testWriteToBigQuery_EnableListInference() throws InterruptedException {
    writeToBigQuery_EnableListInference_Internal("False");
  }

  @Test
  public void testWriteToBigQuery_EnableListInference_AtLeastOnce() throws InterruptedException {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuery_EnableListInference_Internal("True");
  }

  private void writeToBigQuery_ErrorIfExistsSaveMode_Internal(String writeAtLeastOnce)
      throws InterruptedException {
    // initial write
    writeToBigQueryAvroFormat(initialData(), SaveMode.ErrorIfExists, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
    assertThrows(
        expectedExceptionOnExistingTable,
        () -> writeToBigQueryAvroFormat(additonalData(), SaveMode.ErrorIfExists, writeAtLeastOnce));
  }

  @Test
  public void testWriteToBigQuery_ErrorIfExistsSaveMode() throws InterruptedException {
    writeToBigQuery_ErrorIfExistsSaveMode_Internal("False");
  }

  @Test
  public void testWriteToBigQuery_ErrorIfExistsSaveMode_AtLeastOnce() throws InterruptedException {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuery_ErrorIfExistsSaveMode_Internal("True");
  }

  private void writeToBigQuery_IgnoreSaveMode_Internal(String writeAtLeastOnce)
      throws InterruptedException {
    // initial write
    writeToBigQueryAvroFormat(initialData(), SaveMode.Ignore, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
    // second write
    writeToBigQueryAvroFormat(additonalData(), SaveMode.Ignore, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
    assertThat(additionalDataValuesExist()).isFalse();
  }

  @Test
  public void testWriteToBigQuery_IgnoreSaveMode() throws InterruptedException {
    writeToBigQuery_IgnoreSaveMode_Internal("False");
  }

  @Test
  public void testWriteToBigQuery_IgnoreSaveMode_AtLeastOnce() throws InterruptedException {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuery_IgnoreSaveMode_Internal("True");
  }

  private void writeToBigQuery_OverwriteSaveMode_Internal(String writeAtLeastOnce)
      throws InterruptedException {
    // initial write
    writeToBigQueryAvroFormat(initialData(), SaveMode.Overwrite, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();

    // second write
    writeToBigQueryAvroFormat(additonalData(), SaveMode.Overwrite, writeAtLeastOnce);
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isFalse();
    assertThat(additionalDataValuesExist()).isTrue();
  }

  @Test
  public void testWriteToBigQuery_OverwriteSaveMode() throws InterruptedException {
    writeToBigQuery_OverwriteSaveMode_Internal("False");
  }

  @Test
  public void testWriteToBigQuery_OverwriteSaveMode_AtLeastOnce() throws InterruptedException {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuery_OverwriteSaveMode_Internal("True");
  }

  @Test
  public void testWriteToBigQuery_AvroFormat() throws InterruptedException {
    writeToBigQuery(initialData(), SaveMode.ErrorIfExists, "avro");
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
  }

  private void writeToBigQuerySimplifiedApi_Internal(String writeAtLeastOnce)
      throws InterruptedException {
    initialData()
        .write()
        .format("bigquery")
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .save(fullTableName());
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
  }

  @Test
  public void testWriteToBigQuerySimplifiedApi() throws InterruptedException {
    writeToBigQuerySimplifiedApi_Internal("False");
  }

  @Test
  public void testWriteToBigQuerySimplifiedApi_AtLeastOnce() throws InterruptedException {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeToBigQuerySimplifiedApi_Internal("True");
  }

  @Test
  public void testWriteToBigQueryAddingTheSettingsToSparkConf() throws InterruptedException {
    spark.conf().set("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET);
    initialData()
        .write()
        .format("bigquery")
        .option("table", fullTableName())
        .option("writeMethod", writeMethod.toString())
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(2);
    assertThat(initialDataValuesExist()).isTrue();
  }

  private void directWriteToBigQueryWithDiffInSchema_Internal(String writeAtLeastOnce)
      throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName = createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(0);
    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option("table", testDataset + "." + TestConstants.DIFF_IN_SCHEMA_SRC_TABLE_NAME)
            .load();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .save(testDataset + "." + destTableName);
    numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testDirectWriteToBigQueryWithDiffInSchema() throws Exception {
    directWriteToBigQueryWithDiffInSchema_Internal("False");
  }

  @Test
  public void testDirectWriteToBigQueryWithDiffInSchema_AtLeastOnce() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    directWriteToBigQueryWithDiffInSchema_Internal("True");
  }

  private void directWriteToBigQueryWithDiffInSchemaAndDisableModeCheck_Internal(
      String writeAtLeastOnce) throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName = createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE);
    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option("table", testDataset + "." + TestConstants.DIFF_IN_SCHEMA_SRC_TABLE_NAME)
            .load();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .option("enableModeCheckForSchemaFields", false)
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testDirectWriteToBigQueryWithDiffInSchemaAndDisableModeCheck() throws Exception {
    directWriteToBigQueryWithDiffInSchemaAndDisableModeCheck_Internal("False");
  }

  @Test
  public void testDirectWriteToBigQueryWithDiffInSchemaAndDisableModeCheck_AtLeastOnce()
      throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    directWriteToBigQueryWithDiffInSchemaAndDisableModeCheck_Internal("True");
  }

  private void directWriteToBigQueryWithDiffInDescription_Internal(String writeAtLeastOnce)
      throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName = createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(0);
    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option(
                "table",
                testDataset + "." + TestConstants.DIFF_IN_SCHEMA_SRC_TABLE_NAME_WITH_DESCRIPTION)
            .load();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .save(testDataset + "." + destTableName);
    numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testDirectWriteToBigQueryWithDiffInDescription() throws Exception {
    directWriteToBigQueryWithDiffInDescription_Internal("False");
  }

  @Test
  public void testDirectWriteToBigQueryWithDiffInDescription_AtLeastOnce() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    directWriteToBigQueryWithDiffInDescription_Internal("True");
  }

  @Test
  public void testInDirectWriteToBigQueryWithDiffInSchemaAndModeCheck() throws Exception {
    assumeThat(writeMethod, equalTo(SparkBigQueryConfig.WriteMethod.INDIRECT));
    String destTableName = createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE);
    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option("table", testDataset + "." + TestConstants.DIFF_IN_SCHEMA_SRC_TABLE_NAME)
            .load();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("writeMethod", writeMethod.toString())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("enableModeCheckForSchemaFields", true)
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testIndirectWriteToBigQueryWithDiffInSchemaNullableFieldAndDisableModeCheck()
      throws Exception {
    assumeThat(writeMethod, equalTo(SparkBigQueryConfig.WriteMethod.INDIRECT));
    String destTableName = createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE);
    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option("table", testDataset + "." + TestConstants.DIFF_IN_SCHEMA_SRC_TABLE_NAME)
            .load();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("writeMethod", writeMethod.toString())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("enableModeCheckForSchemaFields", false)
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testInDirectWriteToBigQueryWithDiffInDescription() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.INDIRECT));
    String destTableName = createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE);
    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option(
                "table",
                testDataset + "." + TestConstants.DIFF_IN_SCHEMA_SRC_TABLE_NAME_WITH_DESCRIPTION)
            .load();

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  private void writeDFNullableToBigQueryNullable_Internal(String writeAtLeastOnce)
      throws Exception {
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_NULLABLE_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_null", DataTypes.IntegerType, true, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(25), RowFactory.create((Object) null));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(2);
  }

  @Test
  public void testWriteDFNullableToBigQueryNullable() throws Exception {
    writeDFNullableToBigQueryNullable_Internal("False");
  }

  @Test
  public void testWriteDFNullableToBigQueryNullable_AtLeastOnce() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeDFNullableToBigQueryNullable_Internal("True");
  }

  private void writeDFNullableWithNonNullDataToBigQueryRequired_Internal(String writeAtLeastOnce)
      throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REQUIRED_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_req", DataTypes.IntegerType, true, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(25));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("writeAtLeastOnce", writeAtLeastOnce)
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testWriteDFNullableWithNonNullDataToBigQueryRequired() throws Exception {
    writeDFNullableWithNonNullDataToBigQueryRequired_Internal("False");
  }

  @Test
  public void testWriteDFNullableWithNonNullDataToBigQueryRequired_AtLeastOnce() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    writeDFNullableWithNonNullDataToBigQueryRequired_Internal("True");
  }

  @Test
  public void testWriteNullableDFWithNullDataToBigQueryRequired() {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REQUIRED_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_req", DataTypes.IntegerType, true, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create((Object) null));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    Assert.assertThrows(
        "INVALID_ARGUMENT: Errors found while processing rows.",
        Exception.class,
        () ->
            df.write()
                .format("bigquery")
                .mode(SaveMode.Append)
                .option("writeMethod", writeMethod.toString())
                .save(testDataset + "." + destTableName));
  }

  @Test
  public void testWriteNullableDFToBigQueryRepeated() {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REPEATED_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_rep", DataTypes.IntegerType, true, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(10));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    Assert.assertThrows(
        ProvisionException.class,
        () ->
            df.write()
                .format("bigquery")
                .mode(SaveMode.Append)
                .option("writeMethod", writeMethod.toString())
                .save(testDataset + "." + destTableName));
  }

  @Test
  public void testWriteRequiredDFToBigQueryNullable() throws Exception {
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_NULLABLE_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_null", DataTypes.IntegerType, false, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(25));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testWriteRequiredDFToBigQueryRequired() throws Exception {
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REQUIRED_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_req", DataTypes.IntegerType, false, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(25));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save(testDataset + "." + destTableName);
    int numOfRows = testTableNumberOfRows(destTableName);
    assertThat(numOfRows).isEqualTo(1);
  }

  @Test
  public void testWriteRequiredDFToBigQueryRepeated() {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REPEATED_FIELD);
    StructType srcSchema =
        structType(StructField.apply("int_rep", DataTypes.IntegerType, false, Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(10));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    Assert.assertThrows(
        ProvisionException.class,
        () ->
            df.write()
                .format("bigquery")
                .mode(SaveMode.Append)
                .option("writeMethod", writeMethod.toString())
                .save(testDataset + "." + destTableName));
  }

  @Test
  public void testWriteRepeatedDFToBigQueryNullable() {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_NULLABLE_FIELD);
    StructType srcSchema =
        structType(
            StructField.apply(
                "int_null",
                DataTypes.createArrayType(DataTypes.IntegerType),
                true,
                Metadata.empty()));
    List<Row> rows =
        Arrays.asList(RowFactory.create(Arrays.asList(1, 2)), RowFactory.create((Object) null));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    Assert.assertThrows(
        ProvisionException.class,
        () ->
            df.write()
                .format("bigquery")
                .mode(SaveMode.Append)
                .option("writeMethod", writeMethod.toString())
                .save(testDataset + "." + destTableName));
  }

  @Test
  public void testWriteRepeatedDFToBigQueryRequired() {
    assumeThat(writeMethod, equalTo(WriteMethod.DIRECT));
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REQUIRED_FIELD);
    StructType srcSchema =
        structType(
            StructField.apply(
                "int_req",
                DataTypes.createArrayType(DataTypes.IntegerType),
                true,
                Metadata.empty()));
    List<Row> rows = Arrays.asList(RowFactory.create(Arrays.asList(1, 2)));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    Assert.assertThrows(
        ProvisionException.class,
        () ->
            df.write()
                .format("bigquery")
                .mode(SaveMode.Append)
                .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
                .option("writeMethod", writeMethod.toString())
                .save(testDataset + "." + destTableName));
  }

  @Test
  public void testWriteRepeatedDFToBigQueryRepeated() {
    String destTableName =
        createDiffInSchemaDestTable(TestConstants.DIFF_IN_SCHEMA_DEST_TABLE_WITH_REPEATED_FIELD);
    StructType srcSchema =
        structType(
            StructField.apply(
                "int_rep",
                DataTypes.createArrayType(DataTypes.IntegerType),
                true,
                Metadata.empty()));
    List<Row> rows =
        Arrays.asList(RowFactory.create(Arrays.asList(1, 2)), RowFactory.create((Object) null));
    Dataset<Row> df = spark.createDataFrame(rows, srcSchema);

    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save(testDataset + "." + destTableName);
  }

  @Test
  public void testWriteToBigQueryPartitionedAndClusteredTable() {
    // partition write not supported in BQ Storage Write API
    assumeThat(writeMethod, equalTo(SparkBigQueryConfig.WriteMethod.INDIRECT));

    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option("table", TestConstants.LIBRARIES_PROJECTS_TABLE)
            .load()
            .where("platform = 'Sublime'");

    df.write()
        .format("bigquery")
        .option("table", fullTableNamePartitioned())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("partitionField", "created_timestamp")
        .option("clusteredFields", "platform")
        .option("writeMethod", writeMethod.toString())
        .mode(SaveMode.Overwrite)
        .save();

    StandardTableDefinition tableDefinition = testPartitionedTableDefinition();
    assertThat(tableDefinition.getTimePartitioning().getField()).isEqualTo("created_timestamp");
    assertThat(tableDefinition.getClustering().getFields()).contains("platform");
  }

  @Test
  public void testWriteWithTableLabels() throws Exception {
    Dataset<Row> df = initialData();
    spark.conf().set("bigQueryTableLabel.foo", "bar");
    df.write()
        .format("bigquery")
        .option("writeMethod", writeMethod.toString())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .save();
    spark.conf().unset("bigQueryTableLabel.foo");

    Table table =
        IntegrationTestUtils.getBigquery().getTable(TableId.of(testDataset.toString(), testTable));
    assertThat(table).isNotNull();
    Map<String, String> labels = table.getLabels();
    assertThat(labels).isNotNull();
    assertThat(labels).containsEntry("foo", "bar");
  }

  protected Dataset<Row> overwriteSinglePartition(StructField dateField) {
    // create partitioned table
    String tableName = fullTableNamePartitioned() + "_" + id.getAndIncrement();
    TableId tableId = TableId.of(testDataset.toString(), tableName);
    Schema schema =
        Schema.of(
            Field.of("the_date", LegacySQLTypeName.DATE),
            Field.of("some_text", LegacySQLTypeName.STRING));
    TimePartitioning timePartitioning =
        TimePartitioning.newBuilder(TimePartitioning.Type.DAY).setField("the_date").build();
    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder()
            .setSchema(schema)
            .setTimePartitioning(timePartitioning)
            .build();
    bq.create(TableInfo.of(tableId, tableDefinition));
    // entering the data
    try {
      bq.query(
          QueryJobConfiguration.of(
              String.format(
                  "insert into `"
                      + fullTableName()
                      + "` (the_date, some_text) values ('2020-07-01', 'foo'), ('2020-07-02',"
                      + " 'bar')")));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    // overrding a single partition
    List<Row> rows = Arrays.asList(RowFactory.create(Date.valueOf("2020-07-01"), "baz"));
    StructType newDataSchema =
        new StructType(
            new StructField[] {
              dateField, new StructField("some_text", DataTypes.StringType, true, Metadata.empty())
            });
    Dataset<Row> newDataDF = spark.createDataFrame(rows, newDataSchema);

    newDataDF
        .write()
        .format("bigquery")
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("datePartition", "20200701")
        .mode("overwrite")
        .save(fullTableName());

    Dataset<Row> resultDF = spark.read().format("bigquery").load(fullTableName());
    List<Row> result = resultDF.collectAsList();

    assertThat(result).hasSize(2);
    assertThat(result.stream().filter(row -> row.getString(1).equals("bar")).count()).isEqualTo(1);
    assertThat(result.stream().filter(row -> row.getString(1).equals("baz")).count()).isEqualTo(1);

    return resultDF;
  }

  // TODO: figure out why the table creation fails
  // @Test
  public void testOverwriteSinglePartition() {
    overwriteSinglePartition(
        new StructField("the_date", DataTypes.DateType, true, Metadata.empty()));
  }

  // TODO: figure out why the table creation fails
  // @Test
  public void testOverwriteSinglePartitionWithComment() {
    String comment = "the partition field";
    Dataset<Row> resultDF =
        overwriteSinglePartition(
            new StructField("the_date", DataTypes.DateType, true, Metadata.empty())
                .withComment(comment));
    assertThat(resultDF.schema().fields()[0].getComment()).isEqualTo(Some.apply(comment));
  }

  @Test
  public void testWriteToBigQueryWithDescription() {
    String testDescription = "test description";
    String testComment = "test comment";

    Metadata metadata = Metadata.fromJson("{\"description\": \"" + testDescription + "\"}");

    StructType[] schemas =
        new StructType[] {
          structType(new StructField("c1", DataTypes.IntegerType, true, metadata)),
          structType(
              new StructField("c1", DataTypes.IntegerType, true, Metadata.empty())
                  .withComment(testComment)),
          structType(
              new StructField("c1", DataTypes.IntegerType, true, metadata)
                  .withComment(testComment)),
          structType(new StructField("c1", DataTypes.IntegerType, true, Metadata.empty()))
        };

    String[] readValues = new String[] {testDescription, testComment, testComment, null};

    for (int i = 0; i < schemas.length; i++) {
      List<Row> data = Arrays.asList(RowFactory.create(100), RowFactory.create(200));

      Dataset<Row> descriptionDF = spark.createDataFrame(data, schemas[i]);

      descriptionDF
          .write()
          .format("bigquery")
          .mode(SaveMode.Overwrite)
          .option("table", fullTableName() + "_" + i)
          .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
          .option("intermediateFormat", "parquet")
          .option("writeMethod", writeMethod.toString())
          .save();

      Dataset<Row> readDF =
          spark
              .read()
              .format("bigquery")
              .option("dataset", testDataset.toString())
              .option("table", testTable + "_" + i)
              .load();

      Optional<String> description =
          SchemaConverters.getDescriptionOrCommentOfField(
              readDF.schema().fields()[0], Optional.empty());

      if (readValues[i] != null) {
        assertThat(description.isPresent()).isTrue();
        assertThat(description.orElse("")).isEqualTo(readValues[i]);
      } else {
        assertThat(description.isPresent()).isFalse();
      }
    }
  }

  @Test
  public void testWriteEmptyDataFrame() throws Exception {
    Dataset<Row> df = spark.createDataFrame(Collections.emptyList(), Link.class);
    writeToBigQueryAvroFormat(df, SaveMode.Append, "False");
    assertThat(testTableNumberOfRows()).isEqualTo(0);
  }

  private StructType structType(StructField... fields) {
    return new StructType(fields);
  }

  @Test
  public void testPartition_Hourly() {
    testPartition("HOUR");
  }

  @Test
  public void testPartition_Daily() {
    testPartition("DAY");
  }

  @Test
  public void testPartition_Monthly() {
    testPartition("MONTH");
  }

  @Test
  public void testPartition_Yearly() {
    testPartition("YEAR");
  }

  private void testPartition(String partitionType) {
    // partition write not supported in BQ Storage Write API
    assumeThat(writeMethod, equalTo(SparkBigQueryConfig.WriteMethod.INDIRECT));

    List<Data> data =
        Arrays.asList(
            new Data("a", Timestamp.valueOf("2020-01-01 01:01:01")),
            new Data("b", Timestamp.valueOf("2020-01-02 02:02:02")),
            new Data("c", Timestamp.valueOf("2020-01-03 03:03:03")));
    Dataset<Row> df = spark.createDataset(data, Encoders.bean(Data.class)).toDF();
    String table = testDataset.toString() + "." + testTable + "_" + partitionType;
    df.write()
        .format("bigquery")
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("partitionField", "ts")
        .option("partitionType", partitionType)
        .option("partitionRequireFilter", "true")
        .option("table", table)
        .option("writeMethod", writeMethod.toString())
        .save();

    Dataset<Row> readDF = spark.read().format("bigquery").load(table);
    assertThat(readDF.count()).isEqualTo(3);
  }

  @Test
  public void testCacheDataFrameInDataSource() {
    // It takes some time for the data to be available for read via the Storage Read API, after it
    // has been written
    // using the Storage Write API hence this test becomes flaky!
    // Thus ignoring this for V2
    assumeThat(writeMethod, equalTo(SparkBigQueryConfig.WriteMethod.INDIRECT));

    Dataset<Row> allTypesTable = readAllTypesTable();
    writeToBigQuery(allTypesTable, SaveMode.Overwrite, "avro", "False");

    Dataset<Row> df =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .option("readDataFormat", "arrow")
            .load()
            .cache();

    assertThat(df.head()).isEqualTo(allTypesTable.head());

    // read from cache
    assertThat(df.head()).isEqualTo(allTypesTable.head());
    assertThat(df.schema()).isEqualTo(allTypesTable.schema());
  }

  @Test
  public void testWriteJsonToANewTable() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.INDIRECT));
    Dataset<Row> df =
        spark.createDataFrame(
            Arrays.asList(
                RowFactory.create("{\"key\":\"foo\",\"value\":1}"),
                RowFactory.create("{\"key\":\"bar\",\"value\":2}")),
            structType(
                StructField.apply(
                    "jf",
                    DataTypes.StringType,
                    true,
                    Metadata.fromJson("{\"sqlType\":\"JSON\"}"))));
    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", "AVRO")
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("writeMethod", writeMethod.toString())
        .save();

    Table table = bq.getTable(TableId.of(testDataset.toString(), testTable));
    assertThat(table).isNotNull();
    Schema schema = table.getDefinition().getSchema();
    assertThat(schema).isNotNull();
    assertThat(schema.getFields()).hasSize(1);
    assertThat(schema.getFields().get(0).getType()).isEqualTo(LegacySQLTypeName.JSON);

    // validating by querying a sub-field of the json
    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("viewsEnabled", "true")
            .option("materializationDataset", testDataset.toString())
            .load(String.format("SELECT jf.value FROM `%s.%s`", testDataset.toString(), testTable));
    // collecting the data to validate its content
    List<String> result =
        resultDF.collectAsList().stream().map(row -> row.getString(0)).collect(Collectors.toList());
    assertThat(result).containsExactly("1", "2");
  }

  @Test
  public void testWriteJsonToAnExistingTable() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.INDIRECT));
    Table table =
        bq.create(
            TableInfo.of(
                TableId.of(testDataset.toString(), testTable),
                StandardTableDefinition.of(Schema.of(Field.of("jf", LegacySQLTypeName.JSON)))));
    assertThat(table).isNotNull();
    Dataset<Row> df =
        spark.createDataFrame(
            Arrays.asList(
                RowFactory.create("{\"key\":\"foo\",\"value\":1}"),
                RowFactory.create("{\"key\":\"bar\",\"value\":2}")),
            structType(
                StructField.apply(
                    "jf",
                    DataTypes.StringType,
                    true,
                    Metadata.fromJson("{\"sqlType\":\"JSON\"}"))));
    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", "AVRO")
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("writeMethod", writeMethod.toString())
        .save();

    // validating by querying a sub-field of the json
    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("viewsEnabled", "true")
            .option("materializationDataset", testDataset.toString())
            .load(String.format("SELECT jf.value FROM `%s.%s`", testDataset.toString(), testTable));
    // collecting the data to validate its content
    List<String> result =
        resultDF.collectAsList().stream().map(row -> row.getString(0)).collect(Collectors.toList());
    assertThat(result).containsExactly("1", "2");
  }

  @Test
  public void testWriteMapToANewTable() throws Exception {
    Dataset<Row> df =
        spark.createDataFrame(
            Arrays.asList(
                RowFactory.create(ImmutableMap.of("a", new Long(1), "b", new Long(2))),
                RowFactory.create(ImmutableMap.of("c", new Long(3)))),
            structType(
                StructField.apply(
                    "mf",
                    DataTypes.createMapType(DataTypes.StringType, DataTypes.LongType),
                    false,
                    Metadata.empty())));
    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", "AVRO")
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("writeMethod", writeMethod.toString())
        .save();

    Table table = bq.getTable(TableId.of(testDataset.toString(), testTable));
    assertThat(table).isNotNull();
    Schema schema = table.getDefinition().getSchema();
    assertThat(schema).isNotNull();
    assertThat(schema.getFields()).hasSize(1);
    Field mapField = schema.getFields().get(0);
    assertThat(mapField.getType()).isEqualTo(LegacySQLTypeName.RECORD);
    assertThat(mapField.getMode()).isEqualTo(Mode.REPEATED);
    assertThat(mapField.getSubFields())
        .containsExactlyElementsIn(
            Arrays.asList(
                Field.newBuilder("key", LegacySQLTypeName.STRING).setMode(Mode.REQUIRED).build(),
                Field.newBuilder("value", LegacySQLTypeName.INTEGER)
                    .setMode(Mode.NULLABLE)
                    .build()));

    String sql =
        ("SELECT\n"
                + "  (SELECT COUNT(f.key) FROM TABLE, UNNEST(mf) AS f) AS total_keys,\n"
                + "  (SELECT COUNT(*) FROM TABLE) AS total_rows,\n"
                + "  (SELECT f.value FROM TABLE, UNNEST(mf) AS f WHERE f.key='b') AS b_value;")
            .replaceAll("TABLE", testDataset.toString() + "." + testTable);

    // validating by querying a sub-field of the json
    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("viewsEnabled", "true")
            .option("materializationDataset", testDataset.toString())
            .load(sql);
    // collecting the data to validate its content
    Row result = resultDF.head();
    assertThat(result.getLong(0)).isEqualTo(3L);
    assertThat(result.getLong(1)).isEqualTo(2L);
    assertThat(result.getLong(2)).isEqualTo(2L);
  }

  @Test
  public void testAllowFieldAddition() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.INDIRECT));
    StructType initialSchema =
        structType(
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()));
    List<Row> rows =
        Arrays.asList(
            RowFactory.create("val1", Date.valueOf("2023-04-13")),
            RowFactory.create("val2", Date.valueOf("2023-04-14")));
    Dataset<Row> initialDF = spark.createDataFrame(rows, initialSchema);
    // initial write
    initialDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Overwrite)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("intermediateFormat", "avro")
        .option("writeMethod", writeMethod.toString())
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(2);

    StructType finalSchema =
        structType(
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()),
            StructField.apply("new_field", DataTypes.StringType, true, Metadata.empty()));
    List<Row> finalRows =
        Arrays.asList(RowFactory.create("val3", Date.valueOf("2023-04-15"), "newVal1"));
    Dataset<Row> finalDF = spark.createDataFrame(finalRows, finalSchema);
    finalDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("allowFieldAddition", "true")
        .option("allowFieldRelaxation", "true")
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(3);

    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .load();
    List<Row> result = resultDF.collectAsList();
    assertThat(result).hasSize(3);
    assertThat(result.stream().filter(row -> row.getString(2) == null).count()).isEqualTo(2);
    assertThat(
            result.stream()
                .filter(row -> row.getString(2) != null && row.getString(2).equals("newVal1"))
                .count())
        .isEqualTo(1);
  }

  @Test
  public void testWriteToCmekManagedTable() throws Exception {
    String destinationTableKmsKeyName =
        Preconditions.checkNotNull(
            System.getenv("BIGQUERY_KMS_KEY_NAME"),
            "Please set the BIGQUERY_KMS_KEY_NAME to point to a pre-generated and configured KMS key");
    Dataset<Row> df = initialData();
    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("table", fullTableName())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("destinationTableKmsKeyName", destinationTableKmsKeyName)
        .save();

    Table table =
        IntegrationTestUtils.getBigquery().getTable(TableId.of(testDataset.toString(), testTable));
    assertThat(table).isNotNull();
    assertThat(table.getEncryptionConfiguration()).isNotNull();
    assertThat(table.getEncryptionConfiguration().getKmsKeyName())
        .isEqualTo(destinationTableKmsKeyName);
  }

  @Test
  public void testWriteNumericsToWiderFields() throws Exception {
    IntegrationTestUtils.runQuery(
        String.format(
            "CREATE TABLE `%s.%s` (num NUMERIC(10,2), bignum BIGNUMERIC(20,15))",
            testDataset, testTable));
    Decimal num = Decimal.apply("12345.6");
    Decimal bignum = Decimal.apply("12345.12345");
    Dataset<Row> df =
        spark.createDataFrame(
            Arrays.asList(RowFactory.create(num, bignum)),
            structType(
                StructField.apply("num", DataTypes.createDecimalType(6, 1), true, Metadata.empty()),
                StructField.apply(
                    "bignum", DataTypes.createDecimalType(10, 5), true, Metadata.empty())));
    df.write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save();

    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .load();
    List<Row> result = resultDF.collectAsList();
    assertThat(result).hasSize(1);
    Row head = result.get(0);
    assertThat(head.getDecimal(head.fieldIndex("num"))).isEqualTo(new BigDecimal("12345.60"));
    assertThat(head.getDecimal(head.fieldIndex("bignum")))
        .isEqualTo(new BigDecimal("12345.123450000000000"));
  }

  public void testWriteSchemaSubset() throws Exception {
    StructType initialSchema =
        structType(
            StructField.apply("key", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()));
    List<Row> rows =
        Arrays.asList(
            RowFactory.create("key1", "val1", Date.valueOf("2023-04-13")),
            RowFactory.create("key2", "val2", Date.valueOf("2023-04-14")));
    Dataset<Row> initialDF = spark.createDataFrame(rows, initialSchema);
    // initial write
    initialDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(2);

    StructType finalSchema =
        structType(
            StructField.apply("key", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()));
    List<Row> finalRows = Arrays.asList(RowFactory.create("key3", "val3"));
    Dataset<Row> finalDF = spark.createDataFrame(finalRows, finalSchema);
    finalDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(3);
  }

  @Test
  public void allowFieldAdditionWithNestedColumns() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.INDIRECT));
    StructType initialSchema =
        structType(
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()));
    List<Row> rows =
        Arrays.asList(
            RowFactory.create("val1", Date.valueOf("2023-04-13")),
            RowFactory.create("val2", Date.valueOf("2023-04-14")));
    Dataset<Row> initialDF = spark.createDataFrame(rows, initialSchema);
    // initial write
    initialDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Overwrite)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(2);

    StructType nestedSchema =
        structType(
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()),
            StructField.apply(
                "nested_col",
                structType(
                    StructField.apply("sub_field1", DataTypes.StringType, true, Metadata.empty()),
                    StructField.apply("sub_field2", DataTypes.StringType, true, Metadata.empty())),
                true,
                Metadata.empty()));
    List<Row> nestedData =
        Arrays.asList(
            RowFactory.create(
                "val5", Date.valueOf("2023-04-15"), RowFactory.create("str1", "str2")),
            RowFactory.create(
                "val6", Date.valueOf("2023-04-16"), RowFactory.create("str1", "str2")));
    Dataset<Row> nestedDF = spark.createDataFrame(nestedData, nestedSchema);

    nestedDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("allowFieldAddition", "true")
        .option("allowFieldRelaxation", "true")
        .save();

    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .load();
    List<Row> result = resultDF.collectAsList();
    assertThat(result).hasSize(4);
    assertThat(result.stream().filter(row -> row.getAs("nested_col") == null).count()).isEqualTo(2);
    assertThat(
            result.stream()
                .filter(
                    row ->
                        row.getAs("nested_col") != null
                            && row.getAs("nested_col").equals(RowFactory.create("str1", "str2")))
                .count())
        .isEqualTo(2);
  }

  @Test
  public void allowFieldAdditionIntoNestedColumns() throws Exception {
    assumeThat(writeMethod, equalTo(WriteMethod.INDIRECT));
    StructType initialSchema =
        structType(
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()),
            StructField.apply(
                "nested_col",
                structType(
                    StructField.apply("sub_field1", DataTypes.StringType, true, Metadata.empty()),
                    StructField.apply("sub_field2", DataTypes.StringType, true, Metadata.empty())),
                true,
                Metadata.empty()));
    List<Row> initialData =
        Arrays.asList(
            RowFactory.create(
                "val5", Date.valueOf("2023-04-15"), RowFactory.create("str1", "str2")),
            RowFactory.create(
                "val6", Date.valueOf("2023-04-16"), RowFactory.create("str1", "str2")));
    Dataset<Row> initialDF = spark.createDataFrame(initialData, initialSchema);

    initialDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("allowFieldAddition", "true")
        .option("allowFieldRelaxation", "true")
        .save();
    assertThat(testTableNumberOfRows()).isEqualTo(2);

    StructType finalSchema =
        structType(
            StructField.apply("value", DataTypes.StringType, true, Metadata.empty()),
            StructField.apply("ds", DataTypes.DateType, true, Metadata.empty()),
            StructField.apply(
                "nested_col",
                structType(
                    StructField.apply("sub_field1", DataTypes.StringType, true, Metadata.empty()),
                    StructField.apply("sub_field2", DataTypes.StringType, true, Metadata.empty()),
                    StructField.apply("sub_field3", DataTypes.StringType, true, Metadata.empty())),
                true,
                Metadata.empty()));
    List<Row> finalData =
        Arrays.asList(
            RowFactory.create(
                "val5", Date.valueOf("2023-04-15"), RowFactory.create("str1", "str2", "str3")),
            RowFactory.create(
                "val6", Date.valueOf("2023-04-16"), RowFactory.create("str1", "str2", "str3")));
    Dataset<Row> finalDF = spark.createDataFrame(finalData, finalSchema);

    finalDF
        .write()
        .format("bigquery")
        .mode(SaveMode.Append)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("writeMethod", writeMethod.toString())
        .option("allowFieldAddition", "true")
        .option("allowFieldRelaxation", "true")
        .save();

    Dataset<Row> resultDF =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .load();
    List<Row> result = resultDF.collectAsList();
    assertThat(result).hasSize(4);
    assertThat(result.stream().filter(row -> row.getAs("nested_col") != null).count()).isEqualTo(4);
    assertThat(
            result.stream()
                .filter(
                    row -> row.getAs("nested_col").equals(RowFactory.create("str1", "str2", null)))
                .count())
        .isEqualTo(2);
    assertThat(
            result.stream()
                .filter(
                    row ->
                        row.getAs("nested_col").equals(RowFactory.create("str1", "str2", "str3")))
                .count())
        .isEqualTo(2);
  }

  @Test
  public void testWriteSparkMlTypes() {
    // Spark ML types have issues on Spark 2.4
    String sparkVersion = package$.MODULE$.SPARK_VERSION();
    Assume.assumeThat(sparkVersion, CoreMatchers.startsWith("3."));

    Dataset<Row> df =
        spark.createDataFrame(
            Arrays.asList(
                RowFactory.create("1", "20230515", 12345, 5678, 1234.12345),
                RowFactory.create("2", "20230515", 14789, 25836, 1234.12345),
                RowFactory.create("3", "20230515", 54321, 98765, 1234.12345)),
            new StructType(
                new StructField[] {
                  StructField.apply("Seqno", DataTypes.StringType, true, Metadata.empty()),
                  StructField.apply("date1", DataTypes.StringType, true, Metadata.empty()),
                  StructField.apply("num1", DataTypes.IntegerType, true, Metadata.empty()),
                  StructField.apply("num2", DataTypes.IntegerType, true, Metadata.empty()),
                  StructField.apply("amt1", DataTypes.DoubleType, true, Metadata.empty())
                }));

    List<Transformer> stages = new ArrayList<>();

    VectorAssembler va = new VectorAssembler();
    va.setInputCols(new String[] {"num1", "num2"});
    va.setOutputCol("features_vector");
    df = va.transform(df);
    stages.add(va);

    MinMaxScaler minMaxScaler = new MinMaxScaler();
    minMaxScaler.setInputCol("features_vector");
    minMaxScaler.setOutputCol("features");
    MinMaxScalerModel minMaxScalerModel = minMaxScaler.fit(df);
    df = minMaxScalerModel.transform(df);
    stages.add(minMaxScalerModel);

    PipelineModel pipelineModel = new PipelineModel("pipeline", stages);
    df.show(false);
    df.write()
        .format("bigquery")
        .option("writeMethod", writeMethod.toString())
        .option("temporaryGcsBucket", TestConstants.TEMPORARY_GCS_BUCKET)
        .option("dataset", testDataset.toString())
        .option("table", testTable)
        .save();

    Dataset<Row> result =
        spark
            .read()
            .format("bigquery")
            .option("dataset", testDataset.toString())
            .option("table", testTable)
            .load();
    StructType resultSchema = result.schema();
    assertThat(resultSchema.apply("features").dataType()).isEqualTo(SQLDataTypes.VectorType());
    result.show(false);
    List<Row> values = result.collectAsList();
    assertThat(values).hasSize(3);
    Row row = values.get(0);
    assertThat(row.get(row.fieldIndex("features")))
        .isInstanceOf(org.apache.spark.ml.linalg.Vector.class);
  }

  protected long numberOfRowsWith(String name) {
    try {
      return bq.query(
              QueryJobConfiguration.of(
                  String.format("select name from %s where name='%s'", fullTableName(), name)))
          .getTotalRows();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  protected String fullTableName() {
    return testDataset.toString() + "." + testTable;
  }

  protected String fullTableNamePartitioned() {
    return fullTableName() + "_partitioned";
  }

  protected boolean additionalDataValuesExist() {
    return numberOfRowsWith("Xyz") == 1;
  }

  protected boolean initialDataValuesExist() {
    return numberOfRowsWith("Abc") == 1;
  }
}
