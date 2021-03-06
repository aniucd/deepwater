package deepwater.backends.tensorflow.models;

import com.google.common.io.ByteSink;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import deepwater.backends.tensorflow.TensorflowMetaModel;
import org.bytedeco.javacpp.tensorflow;
import org.tensorflow.framework.CollectionDef;
import org.tensorflow.framework.MetaGraphDef;
import org.tensorflow.framework.OpDef;
import org.tensorflow.framework.OpList;
import org.tensorflow.framework.SignatureDef;
import org.tensorflow.framework.TensorInfo;
import org.tensorflow.util.SaverDef;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static deepwater.datasets.FileUtils.findFile;
import static org.bytedeco.javacpp.tensorflow.ReadBinaryProto;
import static org.bytedeco.javacpp.tensorflow.Status;
import static org.tensorflow.framework.CollectionDef.KindCase;


public class ModelFactory {

    static public void debugJar() throws IOException {
        CodeSource src = ModelFactory.class.getProtectionDomain().getCodeSource();
        List<String> list = new ArrayList<>();

        if (src != null) {
            URL jar = src.getLocation();
            ZipInputStream zip = new ZipInputStream(jar.openStream());
            ZipEntry ze;

            while ((ze = zip.getNextEntry()) != null) {
                String entryName = ze.getName();
                list.add(entryName);
                System.out.println(list);
            }

        }
    }

    static public TensorflowModel LoadModel(String modelName) throws Exception {
        return readMetaGraph(modelName);
    }

    private static TensorflowModel readMetaGraph(String metaPath) throws Exception {
        if (!new File(metaPath).exists()) {
            throw new FileNotFoundException(metaPath);
        }

        Path path = FileSystems.getDefault().getPath(metaPath);

        byte[] data = Files.readAllBytes(path);

        MetaGraphDef metaGraphDef = MetaGraphDef.parseFrom(new FileInputStream(metaPath));
        org.tensorflow.framework.GraphDef graphDef = metaGraphDef.getGraphDef();
        // Tags
        for( String tag: metaGraphDef.getMetaInfoDef().getTagsList()) {
            System.out.println(tag);
        }


        OpList ops = metaGraphDef.getMetaInfoDef().getStrippedOpList();
        for( OpDef op: ops.getOpList()){
            System.out.println("stripped op: "+ op.getName());
        }

        for (Map.Entry<String, CollectionDef> entry : metaGraphDef.getCollectionDefMap().entrySet()) {
            System.out.println(entry.getKey() + ":" + entry.getValue());
        }

        // initialize the meta framework
        String metaJson = getFirstOperationFromCollection(metaGraphDef, "meta");

        Gson gson = new Gson();
        TensorflowMetaModel meta;

        if (metaJson.isEmpty()) {
            meta = new TensorflowMetaModel();
        } else {
            meta = gson.fromJson(metaJson, TensorflowMetaModel.class);
        }

        // SaverDef
        SaverDef saver = metaGraphDef.getSaverDef();
        meta.save_filename = saver.getFilenameTensorName();
        meta.save_op = saver.getSaveTensorName();
        meta.restore_op = saver.getRestoreOpName();


        meta.summary_op = getFirstOperationFromCollection(metaGraphDef, "summaries");
        meta.predict_op = getFirstOperationFromCollection(metaGraphDef, "predictions");
        meta.train_op =  getFirstOperationFromCollection(metaGraphDef, "train");
        meta.init = getFirstOperationFromCollection(metaGraphDef, "init");

        getOperationsFromCollection(metaGraphDef, "variables");
        getOperationsFromCollection(metaGraphDef, "trainable_variables");

        /*
        for(Map.Entry<String, SignatureDef> entry: metaGraphDef.getSignatureDefMap().entrySet()) {

            System.out.println("signature: " + entry.getKey());
            for(Map.Entry<String, TensorInfo> input: entry.getValue().getInputsMap().entrySet() ) {
                System.out.println("\tinput: " + input.getKey());
            }

            for(Map.Entry<String, TensorInfo> output: entry.getValue().getOutputsMap().entrySet() ) {
                System.out.println("\toutput: " + output.getKey());
            }
        }

        for(String name: getAllCollections(metaGraphDef)){
            System.out.println("collection: " + name);
        }
        */

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        graphDef.writeTo(output);

        String graphPath = saveToTempFile(new ByteArrayInputStream(output.toByteArray()));

        tensorflow.GraphDef gdef = new tensorflow.GraphDef();
        Status status = ReadBinaryProto(tensorflow.Env.Default(), graphPath, gdef);
        checkStatus(status);
        return new TensorflowModel(meta, gdef, data);
    }

    private static String[] getAllCollections(MetaGraphDef metaGraphDef) throws Exception {
        Set<String> keys = metaGraphDef.getCollectionDefMap().keySet();
        String[] results = new String[keys.size()];
        keys.toArray(results);
        return results;

    }

    private static String getFirstOperationFromCollection(MetaGraphDef metaGraphDef, String collectionName) throws Exception {
        String[] ops = getOperationsFromCollection(metaGraphDef, collectionName);
        if (ops.length > 0){
            return ops[0];
        }
        return "";
    }

    private static String[] getOperationsFromCollection(MetaGraphDef metaGraphDef, String collectionName) throws Exception {
        for(Map.Entry<String, CollectionDef> entry: metaGraphDef.getCollectionDefMap().entrySet()) {
            if (entry.getKey().equals(collectionName)) {
               KindCase kase = entry.getValue().getKindCase();

               switch(kase) {
                   case NODE_LIST: {
                       ProtocolStringList vlist = entry.getValue().getNodeList().getValueList();
                       String[] result = new String[vlist.size()];
                       vlist.toArray(result);
                       return result;
                   }

                   case ANY_LIST: {
                       continue;
                   }
                   case FLOAT_LIST: {
                       continue;
                   }
                   case INT64_LIST: {
                       continue;
                   }
                   case BYTES_LIST: {
                       List<ByteString> vlist = entry.getValue().getBytesList().getValueList();
                       String[] result = new String[vlist.size()];
                       for (int i = 0; i < vlist.size(); i++) {
                           result[i] = new String(vlist.get(i).toByteArray());
                       }
                       return result;
                   }
                default:
                    throw new Exception("invalid collection format.");
                case KIND_NOT_SET:
                    break;
               }
            }
        }
        return new String[]{};
    }

    private static String saveToTempFile(InputStream in) throws IOException {
        String path;
        File temp = File.createTempFile("tempfile", ".tmp");
        BufferedWriter bw = new BufferedWriter(new FileWriter(temp));
        bw.close();
        Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        path = temp.getAbsolutePath();
        return path;
    }

    public static String convertToCanonicalName(String model_name) {
        return model_name.toLowerCase() + ".meta";
    }

    static void checkStatus(Status status) {
        if (!status.ok()) {
            throw new InternalError(status.error_message().getString());
        }
    }

    public static TensorflowModel LoadModelFromFile(String path) {
        try {
            return readMetaGraph(path);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String saveToTempFile(byte[] in) throws IOException {
        String path;
        File temp = File.createTempFile("tempfile", ".tmp");
        ByteSink bs = com.google.common.io.Files.asByteSink(temp);
        bs.write(in);
        path = temp.getAbsolutePath();
        return path;
    }

    public static String findResource(String resourceModelName) throws IOException {

        URL url = Resources.getResource(resourceModelName);
        byte[] modelGraphData = Resources.toByteArray(url);
        String path = saveToTempFile(modelGraphData);

        if (modelGraphData == null || modelGraphData.length == 0) {
            InputStream in = ModelFactory.class.getResourceAsStream("/" + resourceModelName);
            if (in != null) {
                path = saveToTempFile(in);
            } else {
                // NOTE: for some reason inside idea it does not work
                path = findFile("deepwater/tensorflow/src/main/resources/" + resourceModelName);
            }
        }
        return path;
    }

}
