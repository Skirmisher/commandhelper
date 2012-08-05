package com.laytonsmith.persistance;

import com.laytonsmith.PureUtilities.FileUtility;
import com.laytonsmith.PureUtilities.StringUtils;
import com.laytonsmith.PureUtilities.Util;
import com.laytonsmith.PureUtilities.ZipReader;
import static com.laytonsmith.testing.StaticTest.*;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.After;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author lsmith
 */
public class TestPersistance {
    
    /**
     * TODO: Need to test the following:
     * * Removing a key from a data source
     * * Ensuring correct behavior with hidden keys that conflict
     * * 
     */

    public TestPersistance() {
    }
    public Map<String[], String> testData = new HashMap<String[], String>();
    List<File> toDelete = new ArrayList<File>();

    @Before
    public void setUp() {
        testData.put(new String[]{"a", "b"}, "value1");
        testData.put(new String[]{"a", "b", "c1"}, "value2");
        testData.put(new String[]{"a", "b", "c2"}, "value3");
    }

    @After
    public void tearDown() {
        for (File f : toDelete) {
            f.delete();
        }
    }

    @Test
    public void testYML() {
        assertEquals("a:\n"
                + "  b: {c1: value2, c2: value3, _: value1}\n", doOutput("yml://test.yml", testData));
    }

    @Test
    public void testYMLPretty() {
        assertEquals("a:\n"
                + "  b: {\n"
                + "    c1: value2,\n"
                + "    c2: value3,\n"
                + "    _: value1\n"
                + "  }\n", doOutput("prettyprint:yml://testpretty.yml", testData));
    }

    //Dumb properties get loaded in different orders, which doesn't matter, but breaks the
    //string detection here.
//    @Test
//    public void testINI(){
//        assertEquals("a.b=value1\na.b.c2=value3\na.b.c1=value2\n", doOutput("ini://test.ini", testData));
//    }
    @Test
    public void testJSON() {
        assertEquals("{\"a\":{\"b\":{\"c1\":\"value2\",\"c2\":\"value3\",\"_\":\"value1\"}}}\n", doOutput("json://test.json", testData));
    }

    @Test
    @SuppressWarnings("ResultOfObjectAllocationIgnored")
    public void testFilterExceptions() throws URISyntaxException {
        try {
            new DataSourceFilter("$1alias=yml://blah$1.yml\na.*.(**)=$1alias\n", new URI(""));
            fail("Expected an exception when defining numeric alias");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias!=yml://blah$1.yml\na.*.(**)=$alias\n", new URI(""));
            fail("Expected an exception when putting bad characters in a filter");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=yml://blah$1.yml\na.*.(**(=$alias\n", new URI(""));
            fail("Expected an exception when having two left parenthesis");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=yml://blah$1.yml\na.*.(**))=$alias\n", new URI(""));
            fail("Expected an exception when having two right parenthesis");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=yml://blah$1.yml\na.*.(**=$alias\n", new URI(""));
            fail("Expected an exception when having no end parenthesis");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=yml://blah$1.yml\na.*.(**)=$aliasnope\n", new URI(""));
            fail("Expected an exception when using undefined alias");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=yml://blah$2.yml\na.*.(**)=$alias\n", new URI(""));
            fail("Expected an exception when using too high a capture group");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=yml://blah$1.yml\na.*=$alias\na.*=$alias\n", new URI(""));
            fail("Expected an exception when defining the same key twice");
        }
        catch (DataSourceException e) {
            //Pass
        }
        try {
            new DataSourceFilter("$alias=!@#$%^&*()blah$1.yml\na.*.(**)=$alias\n", new URI(""));
            fail("Expected an exception when having an invalid uri");
        }
        catch (DataSourceException e) {
            //Pass
        }
    }

    @Test
    public void testMatch1() throws Exception {
        assertEquals("yml://test.yml", getConnection("a.b.c", "a.b.c=yml://test.yml", "a.b.c.d=yml://no.yml"));
    }

    @Test
    public void testMatch2() throws Exception {
        assertEquals("yml://yes.yml", getConnection("a.b.c", "a.b.*=yml://yes.yml", "a.b.c.d=yml://no.yml"));
    }

    @Test
    public void testMatch3() throws Exception {
        assertEquals("yml://yes.yml", getConnection("a.b.c.d", "a.b.**=yml://no.yml", "a.b.c.*=yml://yes.yml"));
        assertEquals("yml://yes.yml", getConnection("a.b.c.d", "a.b.c.*=yml://yes.yml", "a.b.**=yml://no.yml"));
        assertEquals("yml://yes.yml", getConnection("a.b.c.d", "a.b.(c).(*)=yml://yes.yml", "a.b.**=yml://no.yml"));
    }

    @Test
    public void testMatchCapture1() throws Exception {
        assertEquals("yml://yes.yml", getConnection("a.b.yes", "a.b.(*)=yml://$1.yml"));
    }

    @Test
    public void testMultimatch1() throws Exception {
        assertEquals(getSet("yml://yes.yml"), getConnections("a.b.c", "a.**=yml://yes.yml"));
    }

    @Test
    public void testMultimatch2() throws Exception {
        assertEquals(getSet("yml://yes1.yml", "yml://yes2.yml"), getConnections("a.b.c", "a.**=yml://yes1.yml", "a.b.**=yml://yes2.yml", "b.**=yml://no.yml"));
    }
    
    @Test(expected=UnresolvedCaptureException.class)
    public void testCaptureGetNamespaceException() throws Exception{
        PersistanceNetwork network = new PersistanceNetwork("**=yml://folder/default.yml\nsubset.(*)=yml://folder/$1.yml", new URI(""));
        network.set(new String[]{"subset", "file1"}, "value");
        network.set(new String[]{"subset", "file2"}, "value");
        network.getNamespace(new String[]{"subset"});
    }
    
    @Test
    public void testCaptureGetNamespace() throws Exception{
        PersistanceNetwork network = new PersistanceNetwork("**=yml://folder/default.yml\nsubset.(*)=yml://folder/$1.yml", new URI(""));
        network.set(new String[]{"subset", "subset", "file1"}, "value");
        network.set(new String[]{"subset", "subset", "file2"}, "value");
        Map<String[], String> namespace = new HashMap<String[], String>();
        namespace.put(new String[]{"subset", "subset", "file1"}, "value");
        namespace.put(new String[]{"subset", "subset", "file2"}, "value");
        assertEquals(stringifyMap(namespace), stringifyMap(network.getNamespace(new String[]{"subset", "subset"})));	    
    }
    

    public String doOutput(String uri, Map<String[], String> data) {
        try {
            DataSource ds = DataSourceFactory.GetDataSource(uri);
            if (ds instanceof StringDataSource) {
                Object output = GetPrivate(ds, "output", Object.class);
                if (output instanceof ZipReader) {
                    //It is a file based URI, so we can test this.
                    File outFile = ( (ZipReader) output ).getFile();
                    for (String[] key : data.keySet()) {
                        ds.set(key, data.get(key));
                    }
                    String out = FileUtility.read(outFile);
                    outFile.delete();
                    return out;
                } else {
                    fail("Cannot test non-file based URIs with this method!");
                    return null;
                }
            } else {
                fail("Cannot test non string based data sources with this method!");
                return null;
            }
        }
        catch (Exception ex) {
            fail(Util.GetStacktrace(ex));
            return null;
        }
    }

    File getFileFromDataSource(DataSource ds) {
        if (ds instanceof StringDataSource) {
            Object output = GetPrivate(ds, "output", Object.class);
            if (output instanceof ZipReader) {
                //It is a file based URI, so we can test this.
                File outFile = ( (ZipReader) output ).getFile();
                return outFile;
            }
        }
        return null;
    }

    /**
     * Value should be in the form key.namespace=value, and src is the URI to
     * put the data in
     *
     * @param src
     * @param value
     */
    private void fakeNetwork(String src, String... values) throws Exception {
        DataSource ds = DataSourceFactory.GetDataSource(src);
        File output = getFileFromDataSource(ds);
        if(output != null){
            toDelete.add(output);
        }
        for(String value : values){
            String[] split = value.split("=");
            ds.set(split[0].split("\\."), split[1]);
        }
    }

    public String getConnection(String key, String... mapping) throws Exception {
        return getConnection(key, StringUtils.Join(mapping, "\n"));
    }

    public String getConnection(String key, String mapping) throws Exception {
        DataSourceFilter dsf = new DataSourceFilter(mapping, new URI(""));
        URI conn = dsf.getConnection(key);
        return conn == null ? null : conn.toString();
    }

    public SortedSet<String> getConnections(String key, String... mapping) throws Exception {
        DataSourceFilter dsf = new DataSourceFilter(StringUtils.Join(mapping, "\n"), new URI(""));
        List<URI> uris = dsf.getAllConnections(key);
        SortedSet<String> set = new TreeSet<String>();
        for (URI uri : uris) {
            set.add(uri.toString());
        }
        return set;
    }

    public SortedSet<String> getSet(String... strings) {
        SortedSet<String> set = new TreeSet<String>();
	set.addAll(Arrays.asList(strings));
        return set;
    }
    
    public String stringifyMap(Map<String[], String> map){
        SortedSet<String> append = new TreeSet<String>();
        for(String [] key : map.keySet()){
            append.add(Arrays.toString(key) + "=" + map.get(key));
        }
        return "[" + StringUtils.Join(append, ", ") + "]";
    }
}