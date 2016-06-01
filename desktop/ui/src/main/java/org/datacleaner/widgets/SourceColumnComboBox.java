/**
 * DataCleaner (community edition)
 * Copyright (C) 2014 Neopost - Customer Information Management
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.datacleaner.widgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;

import org.apache.metamodel.MetaModelHelper;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.datacleaner.connection.Datastore;
import org.datacleaner.connection.DatastoreConnection;
import org.datacleaner.util.SchemaComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A combobox that makes it easy to display and select source coumns from a
 * list. The list can either be populated based on a datastore (in which case
 * the list will include all schemas, all tables and all columns) as well as
 * just a single table (in which case it will only include columns from that
 * table).
 */
public class SourceColumnComboBox extends DCComboBox<Object> {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(SourceColumnComboBox.class);

    private final SchemaStructureComboBoxListRenderer _renderer;
    private volatile DatastoreConnection _datastoreConnection;
    private volatile Table _table;
    private final ModelLoadingProgress _modelLoadingProgress = new ModelLoadingProgress();

    private class ModelLoadingProgress {
        private int _currentSchema;
        private int _schemaCount;
        private int _tableCount;
        private ProgressBar _progressBar;

        private ModelLoadingProgress() {
        }

        public void start(int schemaCount) {
            _schemaCount = schemaCount;
            _currentSchema = 0;
            _progressBar = new ProgressBar();
            _progressBar.show();
        }

        public void newSchema(int tableCount) {
            _currentSchema++;
            _tableCount = tableCount;
            _progressBar.update(String.format("Loading schema %d/%d with %d tables...", _currentSchema, _schemaCount,
                    _tableCount));
        }

        public void stop() {
            _progressBar.hide();
        }
    }

    public SourceColumnComboBox() {
        super();
        _renderer = new SchemaStructureComboBoxListRenderer();
        setRenderer(_renderer);
        setEditable(false);
    }

    public SourceColumnComboBox(Datastore datastore) {
        this();
        setModel(datastore);
    }

    public SourceColumnComboBox(Datastore datastore, Table table) {
        this();
        setModel(datastore, table);
    }

    public void setEmptyModel() {
        setModel(null, null);
    }

    public void setModel(Datastore datastore, Table table) {
        final String previousColumnName;
        final Column previousItem = getSelectedItem();
        if (previousItem == null) {
            previousColumnName = null;
        } else {
            previousColumnName = previousItem.getName();
        }

        if (getTable() == table) {
            return;
        }
        setTable(table);

        if (datastore == null) {
            setDatastoreConnection(null);
        } else {
            setDatastoreConnection(datastore.openConnection());
        }
        if (table == null) {
            setModel(new DefaultComboBoxModel<>(new String[1]));
        } else {
            int selectedIndex = 0;

            List<Column> comboBoxList = new ArrayList<>();
            comboBoxList.add(null);

            Column[] columns = table.getColumns();
            for (Column column : columns) {
                comboBoxList.add(column);
                if (column.getName().equals(previousColumnName)) {
                    selectedIndex = comboBoxList.size() - 1;
                }
            }
            final ComboBoxModel<Object> model = new DefaultComboBoxModel<>(comboBoxList.toArray());
            setModel(model);
            setSelectedIndex(selectedIndex);
        }
    }

    public void setModel(Datastore datastore) {
        setModel(datastore, true);
    }

    public void setModel(Table table) {
        setModel(null, table);
    }

    public void setModel(Datastore datastore, boolean retainSelection) {
        setTable(null);

        if (datastore == null) {
            setDatastoreConnection(null);
            setModel(new DefaultComboBoxModel<>(new String[1]));
        } else {
            new Thread(() -> {
                setModelFromSchemas(datastore, retainSelection);
            }).start();
        }
    }

    private void setModelFromSchemas(Datastore datastore, boolean retainSelection) {
        DatastoreConnection con = setDatastoreConnection(datastore.openConnection());

        List<Object> comboBoxList = new ArrayList<>();
        comboBoxList.add(null);

        Schema[] schemas = con.getSchemaNavigator().getSchemas();
        Arrays.sort(schemas, new SchemaComparator());
        _modelLoadingProgress.start(schemas.length);

        for (Schema schema : schemas) {
            _modelLoadingProgress.newSchema(schema.getTableCount());
            comboBoxList.add(schema);

            if (!MetaModelHelper.isInformationSchema(schema)) {
                processSchema(schema.getTables(), comboBoxList, retainSelection);
            }
        }

        final ComboBoxModel<Object> model = new DefaultComboBoxModel<>(comboBoxList.toArray());
        setModel(model);
        _modelLoadingProgress.stop();
    }

    private void processSchema(Table[] tables, List<Object> comboBoxList, boolean retainSelection) {
        final Column previousItem = getSelectedItem();

        for (Table table : tables) {
            try {
                Column[] columns = table.getColumns();

                if (columns != null && columns.length > 0) {
                    comboBoxList.add(table);

                    for (Column column : columns) {
                        comboBoxList.add(column);

                        if (column == previousItem && retainSelection) {
                            setSelectedIndex(comboBoxList.size() - 1);
                        }
                    }
                }
            } catch (Exception e) {
                // errors can occur for experimental datastores (or
                // something like SAS datastores where not all SAS
                // files are supported). Ignore.
                logger.error("Error occurred getting columns of table: {}", table);
            }
        }
    }

    @Override
    public void setSelectedItem(Object value) {
        if (value instanceof String) {
            if (_table == null) {
                // cannot string convert to column without a table.
                value = null;
            } else {
                value = _table.getColumnByName((String) value);
            }
        }
        super.setSelectedItem(value);
    }

    private void setTable(Table table) {
        _table = table;
        setIndentation();
    }

    private void setIndentation() {
        _renderer.setIndentEnabled(_table == null && _datastoreConnection != null);
    }

    public Table getTable() {
        return _table;
    }

    public void addColumnSelectedListener(final DCComboBox.Listener<Column> listener) {
        super.addListener(item -> {
            if (item instanceof Column) {
                listener.onItemSelected((Column) item);
            }
        });
    }

    private DatastoreConnection setDatastoreConnection(DatastoreConnection datastoreConnection) {
        if (_datastoreConnection != null) {
            // close the previous data context provider
            _datastoreConnection.close();
        }
        _datastoreConnection = datastoreConnection;
        setIndentation();
        return _datastoreConnection;
    }

    @Override
    public Column getSelectedItem() {
        Object selectedItem = super.getSelectedItem();
        if (selectedItem instanceof Column) {
            return (Column) selectedItem;
        }
        return null;
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        if (_datastoreConnection != null) {
            // close the data context provider when the widget is removed
            _datastoreConnection.close();
        }
    }
}
