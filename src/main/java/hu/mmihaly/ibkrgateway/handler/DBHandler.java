package hu.mmihaly.ibkrgateway.handler;

import java.sql.*;

public class DBHandler {

    private static DBHandler instance = new DBHandler();

    private static final String connUrl = "jdbc:postgresql://storage:5432/marketdata?user=mmihaly&password=qweASD123";

    private Connection connection;

    private PreparedStatement insertStockStatement;
    private PreparedStatement insertOptionFieldStatement;
    private PreparedStatement insertOptionGreekStatement;

    private int batchSize = 0;

    private DBHandler() {
        try {
            connection = DriverManager.getConnection(connUrl);
            connection.setAutoCommit(true);

            final String stockDataInsert = "insert into stock (d, symbol, price, size) values (?, ?, ?, ?)";
            insertStockStatement = connection.prepareStatement(stockDataInsert);

//            final String fieldDataInsert = "insert into option_fielddata (option, d, fieldname, fieldvalue) values (?, ?, ?, ?)";
//            insertOptionFieldStatement = connection.prepareStatement(fieldDataInsert);

            final String optionGreekInsert = "insert into option_greeks " +
                    "(option, d, bidask, impliedvolatility, delta, gamma, vega, theta, price) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            insertOptionGreekStatement = connection.prepareStatement(optionGreekInsert);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static DBHandler getInstance() {
        return instance;
    }

    public void insertStockData(Timestamp d, String symbol, double price, int size) {
        try {
            insertStockStatement.setTimestamp(1, d);
            insertStockStatement.setString(2, symbol);
            insertStockStatement.setDouble(3, price);
            insertStockStatement.setInt(4, size);
            insertStockStatement.executeUpdate();
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public void createOption(int requestid, String symbol, double strike, char optiontype, String expiry) {
        try {
            final String insertoption = "insert into option (symbol, strike, optiontype, expiry, id) values (?, ?, ?, ?, ?)";
            PreparedStatement createOptionStatement = connection.prepareStatement(insertoption);
            createOptionStatement.setString(1, symbol);
            createOptionStatement.setDouble(2, strike);
            createOptionStatement.setString(3, String.valueOf(optiontype));
            createOptionStatement.setString(4, expiry);
            createOptionStatement.setInt(5, requestid);
            createOptionStatement.executeUpdate();
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public void insertOptionFieldData(int requestid, Timestamp d, String fieldname, String fieldvalue) {
        try {
            insertOptionFieldStatement.setInt(1, requestid);
            insertOptionFieldStatement.setTimestamp(2, d);
            insertOptionFieldStatement.setString(3, fieldname);
            insertOptionFieldStatement.setString(4, fieldvalue);
            insertOptionFieldStatement.executeUpdate();
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public void insertOptionGreeksData(int requestid, Timestamp d, String bidask, double impliedvolatility, double delta, double gamma, double vega, double theta, double price) {
        try {
            insertOptionGreekStatement.setInt(1, requestid);
            insertOptionGreekStatement.setTimestamp(2, d);
            insertOptionGreekStatement.setString(3, bidask);
            insertOptionGreekStatement.setDouble(4, impliedvolatility);
            insertOptionGreekStatement.setDouble(5, delta);
            insertOptionGreekStatement.setDouble(6, gamma);
            insertOptionGreekStatement.setDouble(7, vega);
            insertOptionGreekStatement.setDouble(8, theta);
            insertOptionGreekStatement.setDouble(9, price);
            insertOptionGreekStatement.addBatch();
            if(++batchSize > 1000) {
                insertOptionGreekStatement.executeBatch();
                insertOptionGreekStatement.clearParameters();
                batchSize = 0;
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }
}
