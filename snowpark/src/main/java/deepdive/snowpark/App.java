package deepdive.snowpark;

import com.snowflake.snowpark_java.DataFrame;
import com.snowflake.snowpark_java.Row;
import com.snowflake.snowpark_java.Session;
import com.snowflake.snowpark_java.types.DataTypes;
import com.snowflake.snowpark_java.types.StructField;
import com.snowflake.snowpark_java.types.StructType;

import java.util.HashMap;
import java.util.Map;

/**
 * Hello SnowFlake and SnowPark!
 */
public class App {
    public static void main(String[] args) {
        // Configure Snowflake session parameters
        Map<String, String> config = new HashMap<>();
        config.put("URL", "https://<your_account>.snowflakecomputing.com");
        config.put("USER", "<your_username>");
        config.put("PASSWORD", "<your_password>");
        config.put("ROLE", "<your_role>");
        config.put("WAREHOUSE", "<your_warehouse>");
        config.put("DB", "<your_database>");
        config.put("SCHEMA", "<your_schema>");

        // Create a Snowflake session
        Session session = Session.builder().configs(config).create();


        //define schema and data
        Row[] data = {Row.create(1, "Ram"), Row.create(2, "Hari")};
        StructType schema =
                StructType.create(
                        new StructField("id", DataTypes.IntegerType),
                        new StructField("name", DataTypes.StringType));

        // Create a DataFrame containing specified values.
        DataFrame df = session.createDataFrame(data, schema);

        // Display the DataFrame contents
        df.show();

        // Close the session
        session.close();
    }
}
