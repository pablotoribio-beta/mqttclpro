package in.dc297.mqttclpro.tasker.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import in.dc297.mqttclpro.R;
import in.dc297.mqttclpro.activity.MQTTClientApplication;
import in.dc297.mqttclpro.entity.BrokerEntity;
import in.dc297.mqttclpro.entity.MessageEntity;
import in.dc297.mqttclpro.entity.Topic;
import in.dc297.mqttclpro.helpers.ComparatorHelper;
import in.dc297.mqttclpro.mqtt.internal.Util;
import in.dc297.mqttclpro.tasker.Constants;
import in.dc297.mqttclpro.tasker.PluginBundleManager;
import io.requery.Persistable;
import io.requery.reactivex.ReactiveEntityStore;
import tasker.TaskerPlugin;

import static in.dc297.mqttclpro.tasker.Constants.LOG_TAG;
import static in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_BUNDLE;

/**
 * Created by Deepesh on 10/26/2017.
 */

public class QueryReceiver extends BroadcastReceiver {

    ReactiveEntityStore<Persistable> data = null;
    @Override
    public void onReceive(Context context, Intent intent) {

        if (!in.dc297.mqttclpro.tasker.activity.Intent.ACTION_QUERY_CONDITION.equals(intent.getAction()))
        {
            if (Constants.IS_LOGGABLE)
            {
                Log.e(LOG_TAG,
                        String.format(Locale.US, "Received unexpected Intent action %s", intent.getAction())); //$NON-NLS-1$
            }
            return;
        }

        String topic = "";
        String message = "";
        String topicVar = "";
        long brokerId = 0;
        int taskerMessageId = 0;
        int topicComparator = 0;
        int messageComparator = 0;
        String topicToCompare = "";
        String messageToCompare = "";

        Bundle taskerBundle = intent.getBundleExtra(EXTRA_BUNDLE);
        if(taskerBundle!=null) {
            topic = taskerBundle.getString(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_TOPIC);
            message = taskerBundle.getString(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_MESSAGE);
            topicVar = taskerBundle.getString(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_TOPIC_VAR);
            brokerId = taskerBundle.getLong(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_BROKER_ID);
            topicComparator = taskerBundle.getInt(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_TOPIC_COMPARATOR);
            messageComparator = taskerBundle.getInt(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_MESSAGE_COMPARATOR);
            topicToCompare = taskerBundle.getString(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_TOPIC_COMPARE_TO);
            messageToCompare = taskerBundle.getString(in.dc297.mqttclpro.tasker.activity.Intent.EXTRA_MESSAGE_COMPARE_TO);
        }

        if(TextUtils.isEmpty(topic) || TextUtils.isEmpty(message) || TextUtils.isEmpty(topicVar) || brokerId==0) {//if any of them is still null, we return failure
            setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNKNOWN);
            return;
        }

        taskerMessageId = TaskerPlugin.Event.retrievePassThroughMessageID(intent);

        if(taskerMessageId<=0){
            setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNKNOWN);
            return;
        }

        Log.i(QueryReceiver.class.getName(),"Received query with message ID" + taskerMessageId);

        data = ((MQTTClientApplication)context.getApplicationContext()).getData();
        if(data==null){
            setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNKNOWN);
            return;
        }

        try{
            List<MessageEntity> messageEntityList =  data.select(MessageEntity.class).where(MessageEntity.TASKER_ID.eq(taskerMessageId)).get().toList();
            if(messageEntityList ==null || messageEntityList.size()!=1){
                setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNKNOWN);
                return;
            }
            MessageEntity messageEntity = messageEntityList.get(0);
            if(messageEntity==null){
                setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNKNOWN);
                return;
            }
            String publishedMessage = messageEntity.getPayload();
            String publishedTopic = messageEntity.getDisplayTopic();


            if(!Util.mosquitto_topic_matches_sub(topic, publishedTopic)){
                setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNSATISFIED);
                return;
            }

            long publishedBrokerId = messageEntity.getTopic().getBroker().getId();

            if(publishedBrokerId!=brokerId){
                setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNSATISFIED);
                return;
            }

            String[] comparators = context.getResources().getStringArray(R.array.comparators_array_method);

            if(!TextUtils.isEmpty(topicToCompare)){
                if(!(Boolean)getCustomValue(comparators[topicComparator],publishedTopic,topicToCompare)){
                    setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNSATISFIED);
                    return;
                }
            }

            if(!TextUtils.isEmpty(messageToCompare)){
                if(!(Boolean)getCustomValue(comparators[messageComparator],publishedMessage,messageToCompare)){
                    setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_UNSATISFIED);
                    return;
                }
            }

            setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_SATISFIED);
            if (TaskerPlugin.Setting.hostSupportsVariableReturn(intent.getExtras())) {
                Bundle vars = new Bundle();
                vars.putString("%" + message, publishedMessage);
                vars.putString("%" + topicVar, publishedTopic);
                Log.i("Query success", "Returning var name " + message + " with value " + publishedMessage);
                TaskerPlugin.addVariableBundle(getResultExtras(true), vars);
            } else {
                Log.i("Query success", "Seems like host doesnt support variable setting");
            }


        }
        catch(Exception e){
            e.printStackTrace();
        }

        setResultCode(in.dc297.mqttclpro.tasker.activity.Intent.RESULT_CONDITION_SATISFIED);
    }

    private Object getCustomValue(String methodName, String a, String b){
        Method[] methods = ComparatorHelper.class.getMethods();

        Object value = null;
        for(Method method:methods){
            if(method.getName().equals(methodName)){
                try {
                    value = method.invoke(new ComparatorHelper(), a, b);
                    break;
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }
        return value;
    }
}