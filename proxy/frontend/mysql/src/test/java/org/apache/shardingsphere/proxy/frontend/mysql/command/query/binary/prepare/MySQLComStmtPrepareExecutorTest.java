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

package org.apache.shardingsphere.proxy.frontend.mysql.command.query.binary.prepare;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLCharacterSet;
import org.apache.shardingsphere.db.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.MySQLColumnDefinition41Packet;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.MySQLColumnDefinitionFlag;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.binary.prepare.MySQLComStmtPrepareOKPacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.command.query.binary.prepare.MySQLComStmtPreparePacket;
import org.apache.shardingsphere.db.protocol.mysql.packet.generic.MySQLEofPacket;
import org.apache.shardingsphere.db.protocol.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.db.protocol.packet.DatabasePacket;
import org.apache.shardingsphere.dialect.mysql.exception.UnsupportedPreparedStatementException;
import org.apache.shardingsphere.infra.binder.statement.dml.InsertStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.SelectStatementContext;
import org.apache.shardingsphere.infra.binder.statement.dml.UpdateStatementContext;
import org.apache.shardingsphere.infra.database.type.dialect.MySQLDatabaseType;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.resource.ShardingSphereResourceMetaData;
import org.apache.shardingsphere.infra.metadata.database.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereColumn;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereTable;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.parser.config.SQLParserRuleConfiguration;
import org.apache.shardingsphere.parser.rule.SQLParserRule;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;
import org.apache.shardingsphere.proxy.backend.session.ServerPreparedStatementRegistry;
import org.apache.shardingsphere.proxy.frontend.mysql.ProxyContextRestorer;
import org.apache.shardingsphere.proxy.frontend.mysql.command.query.binary.MySQLServerPreparedStatement;
import org.apache.shardingsphere.proxy.frontend.mysql.command.query.binary.MySQLStatementIDGenerator;
import org.apache.shardingsphere.sql.parser.api.CacheOption;
import org.apache.shardingsphere.sql.parser.sql.dialect.statement.mysql.dml.MySQLInsertStatement;
import org.apache.shardingsphere.sql.parser.sql.dialect.statement.mysql.dml.MySQLSelectStatement;
import org.apache.shardingsphere.sql.parser.sql.dialect.statement.mysql.dml.MySQLUpdateStatement;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Collections;
import java.util.Iterator;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class MySQLComStmtPrepareExecutorTest extends ProxyContextRestorer {
    
    @Mock
    private MySQLComStmtPreparePacket packet;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ConnectionSession connectionSession;
    
    @Before
    public void setup() {
        ProxyContext.init(mock(ContextManager.class, RETURNS_DEEP_STUBS));
        prepareSQLParser();
        prepareMetaData();
        when(connectionSession.getServerPreparedStatementRegistry()).thenReturn(new ServerPreparedStatementRegistry());
        when(connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_CHARACTER_SET_ATTRIBUTE_KEY).get()).thenReturn(MySQLCharacterSet.UTF8MB4_UNICODE_CI);
    }
    
    private void prepareSQLParser() {
        ContextManager contextManager = ProxyContext.getInstance().getContextManager();
        MetaDataContexts metaDataContexts = contextManager.getMetaDataContexts();
        when(metaDataContexts.getMetaData().getGlobalRuleMetaData()).thenReturn(mock(ShardingSphereRuleMetaData.class));
        CacheOption cacheOption = new CacheOption(1024, 1024);
        when(metaDataContexts.getMetaData().getGlobalRuleMetaData().getSingleRule(SQLParserRule.class))
                .thenReturn(new SQLParserRule(new SQLParserRuleConfiguration(false, cacheOption, cacheOption)));
        when(metaDataContexts.getMetaData().getDatabase(connectionSession.getDatabaseName()).getProtocolType()).thenReturn(new MySQLDatabaseType());
    }
    
    private static void prepareMetaData() {
        ShardingSphereTable table = new ShardingSphereTable();
        table.getColumns().put("id", new ShardingSphereColumn("id", Types.BIGINT, true, false, false, false, true));
        table.getColumns().put("name", new ShardingSphereColumn("name", Types.VARCHAR, false, false, false, false, false));
        table.getColumns().put("age", new ShardingSphereColumn("age", Types.SMALLINT, false, false, false, false, true));
        ShardingSphereSchema schema = new ShardingSphereSchema();
        schema.getTables().put("user", table);
        ShardingSphereDatabase database = new ShardingSphereDatabase("db", new MySQLDatabaseType(), new ShardingSphereResourceMetaData("db", Collections.emptyMap()),
                new ShardingSphereRuleMetaData(Collections.emptyList()), Collections.singletonMap("db", schema));
        when(ProxyContext.getInstance().getContextManager().getMetaDataContexts().getMetaData().getDatabase("db")).thenReturn(database);
    }
    
    @Test(expected = UnsupportedPreparedStatementException.class)
    public void assertPrepareMultiStatements() {
        when(packet.getSql()).thenReturn("update t set v=v+1 where id=1;update t set v=v+1 where id=2;update t set v=v+1 where id=3");
        when(connectionSession.getAttributeMap().hasAttr(MySQLConstants.MYSQL_OPTION_MULTI_STATEMENTS)).thenReturn(true);
        when(connectionSession.getAttributeMap().attr(MySQLConstants.MYSQL_OPTION_MULTI_STATEMENTS).get()).thenReturn(0);
        new MySQLComStmtPrepareExecutor(packet, connectionSession).execute();
    }
    
    @Test
    public void assertPrepareSelectStatement() {
        String sql = "select name from db.user where id = ?";
        when(packet.getSql()).thenReturn(sql);
        when(connectionSession.getConnectionId()).thenReturn(1);
        MySQLStatementIDGenerator.getInstance().registerConnection(1);
        Iterator<DatabasePacket<?>> actualIterator = new MySQLComStmtPrepareExecutor(packet, connectionSession).execute().iterator();
        assertThat(actualIterator.next(), instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLEofPacket.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLEofPacket.class));
        assertFalse(actualIterator.hasNext());
        MySQLServerPreparedStatement actualPreparedStatement = connectionSession.getServerPreparedStatementRegistry().getPreparedStatement(1);
        assertThat(actualPreparedStatement.getSql(), is(sql));
        assertThat(actualPreparedStatement.getSqlStatementContext(), instanceOf(SelectStatementContext.class));
        assertThat(actualPreparedStatement.getSqlStatementContext().getSqlStatement(), instanceOf(MySQLSelectStatement.class));
        MySQLStatementIDGenerator.getInstance().unregisterConnection(1);
    }
    
    @Test
    public void assertPrepareSelectSubqueryStatement() {
        String sql = "select *, '' from (select u.id id_alias, name, age from db.user u where id = ?) t";
        when(packet.getSql()).thenReturn(sql);
        int connectionId = 2;
        when(connectionSession.getConnectionId()).thenReturn(connectionId);
        MySQLStatementIDGenerator.getInstance().registerConnection(connectionId);
        Iterator<DatabasePacket<?>> actualIterator = new MySQLComStmtPrepareExecutor(packet, connectionSession).execute().iterator();
        assertThat(actualIterator.next(), instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLEofPacket.class));
        DatabasePacket<?> idColumnDefinitionPacket = actualIterator.next();
        assertThat(idColumnDefinitionPacket, instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(getColumnDefinitionFlag((MySQLColumnDefinition41Packet) idColumnDefinitionPacket),
                is(MySQLColumnDefinitionFlag.PRIMARY_KEY.getValue() | MySQLColumnDefinitionFlag.UNSIGNED.getValue()));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        DatabasePacket<?> ageColumnDefinitionPacket = actualIterator.next();
        assertThat(ageColumnDefinitionPacket, instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(getColumnDefinitionFlag((MySQLColumnDefinition41Packet) ageColumnDefinitionPacket), is(MySQLColumnDefinitionFlag.UNSIGNED.getValue()));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLEofPacket.class));
        assertFalse(actualIterator.hasNext());
        MySQLServerPreparedStatement actualPreparedStatement = connectionSession.getServerPreparedStatementRegistry().getPreparedStatement(1);
        assertThat(actualPreparedStatement.getSql(), is(sql));
        assertThat(actualPreparedStatement.getSqlStatementContext(), instanceOf(SelectStatementContext.class));
        assertThat(actualPreparedStatement.getSqlStatementContext().getSqlStatement(), instanceOf(MySQLSelectStatement.class));
        MySQLStatementIDGenerator.getInstance().unregisterConnection(connectionId);
    }
    
    @Test
    public void assertPrepareInsertStatement() {
        String sql = "insert into user (id, name, age) values (1, ?, ?), (?, 'bar', ?)";
        when(packet.getSql()).thenReturn(sql);
        int connectionId = 2;
        when(connectionSession.getConnectionId()).thenReturn(connectionId);
        when(connectionSession.getDefaultDatabaseName()).thenReturn("db");
        MySQLStatementIDGenerator.getInstance().registerConnection(connectionId);
        Iterator<DatabasePacket<?>> actualIterator = new MySQLComStmtPrepareExecutor(packet, connectionSession).execute().iterator();
        assertThat(actualIterator.next(), instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        DatabasePacket<?> firstAgeColumnDefinitionPacket = actualIterator.next();
        assertThat(firstAgeColumnDefinitionPacket, instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(getColumnDefinitionFlag((MySQLColumnDefinition41Packet) firstAgeColumnDefinitionPacket), is(MySQLColumnDefinitionFlag.UNSIGNED.getValue()));
        DatabasePacket<?> idColumnDefinitionPacket = actualIterator.next();
        assertThat(idColumnDefinitionPacket, instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(getColumnDefinitionFlag((MySQLColumnDefinition41Packet) idColumnDefinitionPacket),
                is(MySQLColumnDefinitionFlag.PRIMARY_KEY.getValue() | MySQLColumnDefinitionFlag.UNSIGNED.getValue()));
        DatabasePacket<?> secondAgeColumnDefinitionPacket = actualIterator.next();
        assertThat(secondAgeColumnDefinitionPacket, instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(getColumnDefinitionFlag((MySQLColumnDefinition41Packet) secondAgeColumnDefinitionPacket), is(MySQLColumnDefinitionFlag.UNSIGNED.getValue()));
        assertThat(actualIterator.next(), instanceOf(MySQLEofPacket.class));
        assertFalse(actualIterator.hasNext());
        MySQLServerPreparedStatement actualPreparedStatement = connectionSession.getServerPreparedStatementRegistry().getPreparedStatement(1);
        assertThat(actualPreparedStatement.getSql(), is(sql));
        assertThat(actualPreparedStatement.getSqlStatementContext(), instanceOf(InsertStatementContext.class));
        assertThat(actualPreparedStatement.getSqlStatementContext().getSqlStatement(), instanceOf(MySQLInsertStatement.class));
        MySQLStatementIDGenerator.getInstance().unregisterConnection(connectionId);
    }
    
    private int getColumnDefinitionFlag(final MySQLColumnDefinition41Packet packet) {
        ByteBuf byteBuf = Unpooled.buffer(22, 22);
        packet.write(new MySQLPacketPayload(byteBuf, StandardCharsets.UTF_8));
        return byteBuf.getUnsignedShortLE(17);
    }
    
    @Test
    public void assertPrepareUpdateStatement() {
        String sql = "update user set name = ?, age = ? where id = ?";
        when(packet.getSql()).thenReturn(sql);
        when(connectionSession.getConnectionId()).thenReturn(1);
        when(connectionSession.getDefaultDatabaseName()).thenReturn("db");
        MySQLStatementIDGenerator.getInstance().registerConnection(1);
        Iterator<DatabasePacket<?>> actualIterator = new MySQLComStmtPrepareExecutor(packet, connectionSession).execute().iterator();
        assertThat(actualIterator.next(), instanceOf(MySQLComStmtPrepareOKPacket.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLColumnDefinition41Packet.class));
        assertThat(actualIterator.next(), instanceOf(MySQLEofPacket.class));
        assertFalse(actualIterator.hasNext());
        MySQLServerPreparedStatement actualPreparedStatement = connectionSession.getServerPreparedStatementRegistry().getPreparedStatement(1);
        assertThat(actualPreparedStatement.getSql(), is(sql));
        assertThat(actualPreparedStatement.getSqlStatementContext(), instanceOf(UpdateStatementContext.class));
        assertThat(actualPreparedStatement.getSqlStatementContext().getSqlStatement(), instanceOf(MySQLUpdateStatement.class));
        MySQLStatementIDGenerator.getInstance().unregisterConnection(1);
    }
    
    @Test(expected = UnsupportedPreparedStatementException.class)
    public void assertPrepareNotAllowedStatement() {
        when(packet.getSql()).thenReturn("begin");
        new MySQLComStmtPrepareExecutor(packet, connectionSession).execute();
    }
}
