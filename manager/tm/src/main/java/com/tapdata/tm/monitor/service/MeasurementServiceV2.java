package com.tapdata.tm.monitor.service;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.result.DeleteResult;
import com.tapdata.manager.common.utils.JsonUtil;
import com.tapdata.tm.base.dto.Page;
import com.tapdata.tm.commons.dag.nodes.DatabaseNode;
import com.tapdata.tm.commons.dag.process.TableRenameProcessNode;
import com.tapdata.tm.commons.schema.MetadataInstancesDto;
import com.tapdata.tm.commons.task.dto.TaskDto;
import com.tapdata.tm.config.security.UserDetail;
import com.tapdata.tm.metadatainstance.service.MetadataInstancesService;
import com.tapdata.tm.monitor.constant.Granularity;
import com.tapdata.tm.monitor.dto.TableSyncStaticDto;
import com.tapdata.tm.monitor.entity.MeasurementEntity;
import com.tapdata.tm.monitor.param.AggregateMeasurementParam;
import com.tapdata.tm.monitor.param.MeasurementQueryParam;
import com.tapdata.tm.monitor.vo.TableSyncStaticVo;
import com.tapdata.tm.task.service.TaskRecordService;
import com.tapdata.tm.task.service.TaskService;
import com.tapdata.tm.utils.FunctionUtils;
import com.tapdata.tm.utils.TimeUtil;
import io.tapdata.common.sample.request.Sample;
import io.tapdata.common.sample.request.SampleRequest;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
@Setter(onMethod_ = {@Autowired})
public class MeasurementServiceV2 {
    private MongoTemplate mongoOperations;
    private TaskRecordService taskRecordService;
    private MetadataInstancesService metadataInstancesService;
    private TaskService taskService;

    public void addAgentMeasurement(List<SampleRequest> samples) {
        addBulkAgentMeasurement(samples, Granularity.GRANULARITY_MINUTE);
    }

    private void addBulkAgentMeasurement(List<SampleRequest> sampleRequestList, String granularity) {
        BulkOperations bulkOperations = mongoOperations.bulkOps(BulkOperations.BulkMode.UNORDERED, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME);
        for (SampleRequest singleSampleRequest : sampleRequestList) {
            Criteria criteria = Criteria.where(MeasurementEntity.FIELD_GRANULARITY).is(granularity);
            Date theDate = TimeUtil.cleanTimeAfterMinute(new Date());
            criteria.and(MeasurementEntity.FIELD_DATE).is(theDate);

            Map<String, String> tags = singleSampleRequest.getTags();
            if (null == tags || 0 == tags.size()) {
                continue;
            }

            for (Map.Entry<String, String> entry : tags.entrySet()) {
                criteria.and(MeasurementEntity.FIELD_TAGS + "." + entry.getKey()).is(entry.getValue());
            }
            Query query = Query.query(criteria);

            Map<String, Object> sampleMap = singleSampleRequest.getSample().toMap();
            Document upd = new Document();
            upd.put("$each", Collections.singletonList(sampleMap));
            upd.put("$slice", 200); //为了保护数组过长， 在出bug的情况下
            upd.put("$sort", new Document().append(Sample.FIELD_DATE, -1));

            Update update = new Update().push(MeasurementEntity.FIELD_SAMPLES, upd)
                    .min(MeasurementEntity.FIELD_FIRST, singleSampleRequest.getSample().getDate())
                    .max(MeasurementEntity.FIELD_LAST, singleSampleRequest.getSample().getDate());
            bulkOperations.upsert(query, update);
        }

        BulkWriteResult bulkWriteResult = bulkOperations.execute();
        log.info("add bulkWriteResult,{}", bulkWriteResult);
    }

    private static final String TAG_FORMAT = String.format("%s.%%s", MeasurementEntity.FIELD_TAGS);
    private static final String FIELD_FORMAT = String.format("%s.%s.%%s",
            MeasurementEntity.FIELD_SAMPLES, Sample.FIELD_VALUES);
    private static final String INSTANT_PADDING_LEFT = "left";
    private static final String INSTANT_PADDING_RIGHT = "right";


    public Object getSamples(MeasurementQueryParam measurementQueryParam) {
        Map<String, List<Map<String, Object>>> data = new HashMap<>();

        long initialStart = measurementQueryParam.getStartAt();
        long initialEnd = measurementQueryParam.getEndAt();

        boolean hasTimeline = false;
        List<Long> timeline = null;
        Long timelineInterval = null;
        for(String unique : measurementQueryParam.getSamples().keySet()) {
            data.putIfAbsent(unique, new ArrayList<>());
            List<Map<String, Object>> uniqueData = data.get(unique);
            MeasurementQueryParam.MeasurementQuerySample querySample = measurementQueryParam.getSamples().get(unique);
            long start = null != querySample.getStartAt() ? querySample.getStartAt() : initialStart;
            long end = null != querySample.getEndAt() ? querySample.getEndAt() : initialEnd;
            switch (querySample.getType()) {
                case MeasurementQueryParam.MeasurementQuerySample.MEASUREMENT_QUERY_SAMPLE_TYPE_INSTANT:
                    Map<String, Sample> instantSamples = getInstantSamples(querySample, end, INSTANT_PADDING_RIGHT);
                    uniqueData.addAll(formatSingleSamples(instantSamples));
                    break;
                case MeasurementQueryParam.MeasurementQuerySample.MEASUREMENT_QUERY_SAMPLE_TYPE_DIFFERENCE:
                    Map<String, Sample> diffSamples = getDifferenceSamples(querySample, start, end);
                    uniqueData.addAll(formatSingleSamples(diffSamples));
                    break;
                case MeasurementQueryParam.MeasurementQuerySample.MEASUREMENT_QUERY_SAMPLE_TYPE_CONTINUOUS:
                    hasTimeline = true;
                    String granularity = Granularity.calculateReasonableGranularity(start, end);
                    timelineInterval = Granularity.getTimelineMillisInterval(granularity);
                    if (!granularity.equals(Granularity.GRANULARITY_MINUTE)) {
                        // t1 start      t2            t3           t4      end  t5
                        // |__#____*_*___|___*__*___*__|___*_____*__|____*___#___|
                        //         s1 s2     s3 s4  s5      s6   s7      s8

                        // move the start cursor to the former granularity section
                        if (start % timelineInterval != 0) {
                            start = ((start / timelineInterval) - 1) * timelineInterval;
                        }
                        // move the start cursor to the current granularity section
                        if (end % timelineInterval != 0) {
                            end = ((end / timelineInterval)) * timelineInterval;
                        }
                    }
                    timeline = getTimeline(start, end, timelineInterval);
                    Map<String, List<Sample>> continuousSamples = getContinuousSamples(querySample, start, end);

                    // calculate the last point value
                    if (!granularity.equals(Granularity.GRANULARITY_MINUTE)) {
                        String previousGranularity = Granularity.getPreviousLevelGranularity(granularity);
                        long current = ((System.currentTimeMillis() / timelineInterval)) * timelineInterval;
                        for(int idx = 1; idx < timeline.size(); ++idx) {
                            if (current > timeline.get(idx-1) && current <= timeline.get(idx)) {
                                long time = timeline.get(idx);
                                // get the sample data in [start, end)
                                Criteria criteria = Criteria.where(MeasurementEntity.FIELD_DATE).gte(new Date(time)).lt(new Date(time + timelineInterval));
                                criteria.and(MeasurementEntity.FIELD_GRANULARITY).is(previousGranularity);
                                for (Map.Entry<String, String> entry : querySample.getTags().entrySet()) {
                                    criteria.and(String.format(TAG_FORMAT, entry.getKey())).is(entry.getValue());
                                }

                                List<String> includedFields = new ArrayList<>();
                                includedFields.add(MeasurementEntity.FIELD_DATE);
                                includedFields.add(MeasurementEntity.FIELD_TAGS);
                                for (String field : querySample.getFields()) {
                                    includedFields.add(String.format(FIELD_FORMAT, field));
                                }

                                Query query = new Query(criteria);
                                query.fields().include(includedFields.toArray(new String[]{}));
                                query.with(Sort.by(MeasurementEntity.FIELD_DATE).ascending());

                                for (MeasurementEntity entity : mongoOperations.find(query, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME)) {
                                    String hash = hashTag(entity.getTags());
                                    continuousSamples.putIfAbsent(hash, new ArrayList<>());
                                    Sample sample = new Sample();
                                    sample.setDate(new Date(timeline.get(idx)));
                                    sample.setVs(entity.averageValues());
                                    continuousSamples.get(hash).add(sample);
                                }
                                break;
                            }
                        }
                    }

                    List<Map<String, Object>> formatContinuousSamples = formatContinuousSamples(continuousSamples, timeline, timelineInterval);
                    uniqueData.addAll(formatContinuousSamples);
                    break;
            }
        }

        Map<String, Object> ret = new HashMap<>();
        ret.put("samples", data);
        if (hasTimeline && null != timeline) {
            ret.put("time", timeline);
            ret.put("interval", timelineInterval);
        }

        return ret;
    }


    /**
     *  padding is a value to tolerance the data loss.
     *  diagram:
     *      #: time
     *      *: sample data point
     *
     *   time1                                          time2
     *  |__#____*_*___|___*__*___*__|___*_____*__|____*___#___|
     *         s1 s2     s3 s4  s5      s6   s7      s8
     *  when query instant value of time1, but the first sample value is s1, here we use `padding=left` to tolerance
     *  the data loss of the left part, aka. we got s1 for the query.
     *  when query instant value of time2, but the last sample value is s8, here we use `padding=right` to tolerance
     *  the data loss of the right part, aka. we got s2 for the query.
     * @param querySample
     * @param time
     * @param padding
     * @return
     */
    private Map<String, Sample> getInstantSamples(MeasurementQueryParam.MeasurementQuerySample querySample, long time, String padding) {
        Map<String, Sample> data = new HashMap<>();
        if (!StringUtils.equalsAny(querySample.getType(),
                MeasurementQueryParam.MeasurementQuerySample.MEASUREMENT_QUERY_SAMPLE_TYPE_INSTANT,
                MeasurementQueryParam.MeasurementQuerySample.MEASUREMENT_QUERY_SAMPLE_TYPE_DIFFERENCE)) {
            return data;
        }

        Date date = TimeUtil.cleanTimeAfterMinute(new Date(time));
        Criteria criteria = Criteria.where(MeasurementEntity.FIELD_DATE);
        SortOperation sort;
        switch (padding) {
            case INSTANT_PADDING_LEFT:
                criteria = criteria.gte(date);
                sort = Aggregation.sort(Sort.by(MeasurementEntity.FIELD_DATE).ascending());
                break;
            case INSTANT_PADDING_RIGHT:
                criteria = criteria.lte(date);
                sort = Aggregation.sort(Sort.by(MeasurementEntity.FIELD_DATE).descending());
                break;
            default:
                throw new RuntimeException("invalid padding value when get instant value");
        }
        criteria.and(MeasurementEntity.FIELD_GRANULARITY).is(Granularity.GRANULARITY_MINUTE);

        for (Map.Entry<String, String> entry : querySample.getTags().entrySet()) {
            criteria.and(String.format(TAG_FORMAT, entry.getKey())).is(entry.getValue());
        }

        MatchOperation match = Aggregation.match(criteria);
        GroupOperation group = Aggregation.group(MeasurementEntity.FIELD_TAGS)
                .first(MeasurementEntity.FIELD_DATE).as(MeasurementEntity.FIELD_DATE)
                .first(MeasurementEntity.FIELD_TAGS).as(MeasurementEntity.FIELD_TAGS)
                .first(MeasurementEntity.FIELD_SAMPLES).as(MeasurementEntity.FIELD_SAMPLES);
        // match should be at the first param, sort should be the second while group be the last
        Aggregation aggregation = Aggregation.newAggregation( match, sort, group);
        AggregationResults<MeasurementEntity> results = mongoOperations.aggregate(aggregation, MeasurementEntity.COLLECTION_NAME, MeasurementEntity.class);
        List<MeasurementEntity> entities = results.getMappedResults();
        for (MeasurementEntity entity : entities) {
            String hash = hashTag(entity.getTags());
            for(Sample sample : entity.getSamples()) {
                if (!data.containsKey(hash)) {
                    data.put(hash, sample);
                    continue;
                }
                long oldInterval = Math.abs(data.get(hash).getDate().getTime() - time);
                long newInterval = Math.abs(sample.getDate().getTime() - time);
                if (newInterval < oldInterval) {
                    data.put(hash, sample);
                }
            }
        }

        List<String> fields = querySample.getFields();
        for (String hash : data.keySet()) {
            Sample sample = data.get(hash);
            Map<String, Number> values = new HashMap<>();
            for (Map.Entry<String, Number> entry : sample.getVs().entrySet()) {
                if (fields.contains(entry.getKey())) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
            sample.setVs(values);
        }

        return data;
    }

    public Map<String, Sample> getDifferenceSamples(MeasurementQueryParam.MeasurementQuerySample querySample, long start, long end) {
        Map<String, Sample> endSamples = getInstantSamples(querySample, end, INSTANT_PADDING_RIGHT);
        Map<String, Sample> startSamples = getInstantSamples(querySample, start, INSTANT_PADDING_LEFT);

        Map<String, Sample> data = new HashMap<>();
        for (String hash : endSamples.keySet()) {
            if (!startSamples.containsKey(hash)) {
                continue;
            }

            Sample ret = new Sample();
            ret.setVs(new HashMap<>());
            Sample startSample = startSamples.get(hash);
            Sample endSample = endSamples.get(hash);
            for (String key : endSample.getVs().keySet()) {
                if (!startSample.getVs().containsKey(key)) {
                    continue;
                }
                Number diff = endSample.getVs().get(key).doubleValue() - startSample.getVs().get(key).doubleValue();
                ret.getVs().put(key, diff);
            }
            data.put(hash, ret);
        }

        return data;
    }

    private Map<String, List<Sample>> getContinuousSamples(MeasurementQueryParam.MeasurementQuerySample querySample, long start, long end) {
        Map<String, List<Sample>> data = new HashMap<>();
        if (!StringUtils.equalsAny(querySample.getType(),
                MeasurementQueryParam.MeasurementQuerySample.MEASUREMENT_QUERY_SAMPLE_TYPE_CONTINUOUS)) {
            return data;
        }

        Criteria criteria = Criteria.where(String.format("%s.%s", MeasurementEntity.FIELD_SAMPLES, Sample.FIELD_DATE))
                .gte(new Date(start))
                .lte(new Date(end));
        criteria.and(MeasurementEntity.FIELD_GRANULARITY).is(Granularity.calculateReasonableGranularity(start, end));
        for (Map.Entry<String, String> entry : querySample.getTags().entrySet()) {
            criteria.and(String.format(TAG_FORMAT, entry.getKey())).is(entry.getValue());
        }

        List<String> includedFields = new ArrayList<>();
        includedFields.add(MeasurementEntity.FIELD_TAGS);
        includedFields.add(String.format("%s.%s", MeasurementEntity.FIELD_SAMPLES, Sample.FIELD_DATE));
        for (String field : querySample.getFields()) {
            includedFields.add(String.format(FIELD_FORMAT, field));
        }

        Query query = new Query(criteria);
        query.fields().include(includedFields.toArray(new String[]{}));
        query.with(Sort.by(MeasurementEntity.FIELD_DATE).ascending());
        List<MeasurementEntity> entities = mongoOperations.find(query, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME);
        for (MeasurementEntity entity : entities) {
            String hash = hashTag(entity.getTags());
            if (!data.containsKey(hash)) {
                data.put(hash, new ArrayList<>());
            }
            data.get(hash).addAll(entity.getSamples());
        }

        return data;
    }

    private String hashTag(Map<String, String> tags) {
        StringBuilder sb = new StringBuilder();
        for(String key: tags.keySet().stream().sorted().collect(Collectors.toList())) {
            sb.append(String.format("%s:%s", key, tags.get(key)));
            sb.append(";");
        }

        return sb.toString();
    }

    private Map<String, String> reverseHashTag(String hash) {
        Map<String, String> tags = new HashMap<>();
        for(String pair : hash.split(";")) {
            String[] kv = pair.split(":");
            tags.put(kv[0], kv[1]);
        }

        return tags;
    }

    private List<Map<String, Object>> formatSingleSamples(Map<String, Sample> singleSamples) {
        List<Map<String, Object>> data = new ArrayList<>();
        for(Map.Entry<String, Sample> entry: singleSamples.entrySet()) {
            Map<String, String> tags = reverseHashTag(entry.getKey());
            Map<String, Object> values = new HashMap<>(entry.getValue().getVs());
            values.put("tags", tags);
            data.add(values);
        }

        return data;
    }

    private  List<Map<String, Object>> formatContinuousSamples(Map<String, List<Sample>> continuousSamples, List<Long> timeline, long interval) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> entry : continuousSamples.entrySet()) {
            Map<String, String> tags = reverseHashTag(entry.getKey());
            Map<String, Number[]> values = new HashMap<>();

            List<Sample> samples = entry.getValue().stream().sorted(Comparator.comparing(Sample::getDate)).collect(Collectors.toList());

            int timeLineIdx = 0;
            int sampleIdx1 = 0;
            while (timeLineIdx < timeline.size() && sampleIdx1 < samples.size()) {
                Sample sample1 = samples.get(sampleIdx1);
                long time1 = sample1.getDate().getTime();
                long gap1 = Math.abs(timeline.get(timeLineIdx) - time1);

                if (gap1 > interval / 2) {
                    if (timeline.get(timeLineIdx) - time1 > 0) {
                        //    s1          s2          s3
                        //    \/          \/          \/
                        // |___*_______|___*_______|___*_______|
                        //             t1          t2          t3
                        // the s1 is left of the ranging area of t1, so s1 is dropped, but we still have to find the
                        // data for t1, so we move the pointer of s to s2 while keeping the pointer of t unchanged.
                        sampleIdx1 += 1;
                    } else {
                        //         s1          s2          s3
                        //         \/          \/          \/
                        // |________*__|________*__|________*__|
                        // t1         t2         t3           t4
                        // the s1 is right of the ranging area of t1, so the t1 does not have a value(aka. null), so
                        // we move the pointer of t to t2 while keeping the pointer of s unchanged.
                        timeLineIdx += 1;
                    }
                    continue;
                }

                //         s1
                //         \/
                // |________*__|__________|__________|
                //             t1          t2          t3
                // the s1 is in the ranging area of t1, so set data of s1 to t1 temporarily.
                for(String key : sample1.getVs().keySet()) {
                    values.putIfAbsent(key, new Number[timeline.size()]);
                    values.get(key)[timeLineIdx] = sample1.getVs().get(key);
                }

                //         s1   s2
                //         \/   \/
                // |________*__|_*_________|__________|
                //             t1          t2         t3
                // s2 may have the shorter distance to t1, we never know, so we should iterate the rest data
                // of s until get out the ranging area of t1.
                boolean skipSet = false;
                int sampleIdx2 = sampleIdx1;
                while(sampleIdx2 < samples.size() - 1) {
                    sampleIdx2 += 1;
                    Sample sample2 = samples.get(sampleIdx2);
                    long time2 = sample2.getDate().getTime();
                    long gap2 = Math.abs(timeline.get(timeLineIdx) - time2);
                    // pointer is out of the t1 ranging area, break the loop
                    if (gap2 > interval / 2) {
                        break;
                    }

                    // the shortest distance data is found, move pointer of s out of the t1 ranging area
                    if (skipSet || gap1 < gap2) {
                        continue;
                    }

                    // set the new value into array, only if the gap is smaller than the former one
                    for(String key : sample2.getVs().keySet()) {
                        values.putIfAbsent(key, new Number[timeline.size()]);
                        values.get(key)[timeLineIdx] = sample2.getVs().get(key);
                    }
                    // got a new value, use the new gap to compare
                    gap1 = gap2;

                    //         s1s2s3 s4 s5
                    //         \/\/\/ \/ \/
                    // |________*_*_*|_*__*_______|__________|
                    //              t1           t2         t3
                    // skip the set only if the pointer of s reaches s4 since the gap is
                    // surely getting bigger.
                    if (timeline.get(timeLineIdx) - time2 < 0) {
                        skipSet = true;
                    }

                }
                timeLineIdx += 1;
                sampleIdx1 = sampleIdx2;
            }
            Map<String, Object> single = new HashMap<>(values);
            single.put("tags", tags);
            data.add(single);
        }

        return data;
    }

    private List<Long> getTimeline(long start, long end, long interval) {
        // get the time trail with same interval
        List<Long> timeline = new ArrayList<>();
        while (start < end) {
            timeline.add(end);
            end -= interval;
        }

        return timeline.stream().sorted().collect(Collectors.toList());
    }


    public void aggregateMeasurement(AggregateMeasurementParam param) {
        if (!param.isGranularityValid()) {
            throw new RuntimeException("invalid value for granularity: " + param.getGranularity());
        }

        if (!param.isStartEndValid()) {
            String msg = "invalid value for start or end, start: %s, end: %s;";
            throw new RuntimeException(String.format(msg, param.getStart(), param.getEnd()));
        }

        for (String granularity : param.getGranularity()) {
            aggregateMeasurementByGranularity(param.getTags(), param.getStart(), param.getEnd(), granularity);
        }
    }


    public void aggregateMeasurementByGranularity(Map<String, String> queryTags, long start, long end, String granularity) {
        long interval = Granularity.getGranularityMillisInterval(granularity);
        String nextGranularity = Granularity.getNextLevelGranularity(granularity);

        //      start                    end
        // |______*____|__________|_______*___|
        // t1         t2         t3         t4
        // here we should use t2 as start and t3 as stop.

        // move the start cursor to the next granularity section
        if (start % interval != 0) {
            start = ((start / interval) + 1) * interval;
        }
        // move the start cursor to the former granularity section
        if (end % interval != 0) {
            end = ((end / interval) - 1) * interval;
        }
        // |______*____|_____*_____|__________|
        // t1         t2         t3         t4
        // skip the aggregate since sections are not fully completed
        if (end - start < interval) {
            return;
        }

        // get the sample data in [start, end)
        Criteria criteria = Criteria.where(MeasurementEntity.FIELD_DATE).gte(new Date(start)).lt(new Date(end));
        criteria.and(MeasurementEntity.FIELD_GRANULARITY).is(granularity);
        for (Map.Entry<String, String> entry : queryTags.entrySet()) {
            criteria.and(String.format(TAG_FORMAT, entry.getKey())).is(entry.getValue());
        }

        List<String> includedFields = new ArrayList<>();
        includedFields.add(MeasurementEntity.FIELD_DATE);
        includedFields.add(MeasurementEntity.FIELD_TAGS);
        includedFields.add(MeasurementEntity.FIELD_SAMPLES);

        Query query = new Query(criteria);
        query.fields().include(includedFields.toArray(new String[]{}));
        query.with(Sort.by(MeasurementEntity.FIELD_DATE).ascending());

        Map<String, List<MeasurementEntity>> tagEntities = new HashMap<>();
        for (MeasurementEntity entity : mongoOperations.find(query, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME)) {
            String hash = hashTag(entity.getTags());
            tagEntities.putIfAbsent(hash, new ArrayList<>());
            tagEntities.get(hash).add(entity);
        }

        // does not have samples to be aggregated
        if (tagEntities.isEmpty()) {
            return;
        }

        BulkOperations bulkOperations = null;
        for (Map.Entry<String, List<MeasurementEntity>> entry : tagEntities.entrySet()) {
            int idx = 0;

            for (int cnt = 1; cnt <= (end - start) / interval; ++cnt) {
                long innerStart = start + (cnt - 1) * interval;
                long innerEnd = innerStart + interval;

                Long first = null, last = null;
                List<MeasurementEntity> entities = entry.getValue();
                List<Map<String, Object>> samples = new ArrayList<>();
                // the last value should not be included, it should be [start, end)
                for (long time = innerStart; time < innerEnd && idx < entry.getValue().size(); time += interval) {
                    MeasurementEntity entity = entities.get(idx);
                    if (entity.getDate().getTime() > time) {
                        continue;
                    }

                    if (null == first || first > time) {
                        first = time;
                    }
                    if (null == last || last < time) {
                        last = time;
                    }

                    Sample nextGranularitySample = new Sample();
                    nextGranularitySample.setDate(new Date(time));
                    nextGranularitySample.setVs(entity.averageValues());
                    samples.add(nextGranularitySample.toMap());

                    idx += 1;
                }

                if (samples.isEmpty()) {
                    continue;
                }

                Date nextGranularityDate = Granularity.calculateGranularityDate(nextGranularity, new Date(innerStart));

                Criteria upsertCriteria = Criteria.where(MeasurementEntity.FIELD_GRANULARITY).is(nextGranularity);
                upsertCriteria.and(MeasurementEntity.FIELD_TAGS).is(reverseHashTag(entry.getKey()));
                upsertCriteria.and(MeasurementEntity.FIELD_DATE).is(nextGranularityDate);

                Document ss = new Document();
                ss.put("$each", samples);
                // TODO(dexter): find a more elegant way to de-duplicate when value with same date arrives
                // each time we call the function, the inner doc is hard to de-duplicate, here we add
                // $slice=200 to protect the document from being to huge, the duplicate data will not
                // affect the data since the generated value will always be the same.
                ss.put("$slice", 200);
                ss.put("$sort", new Document().append(Sample.FIELD_DATE, -1));
                Update update = new Update().push(MeasurementEntity.FIELD_SAMPLES, ss)
                        .min(MeasurementEntity.FIELD_FIRST, new Date(first))
                        .max(MeasurementEntity.FIELD_LAST, new Date(last));

                if (null == bulkOperations) {
                    bulkOperations = mongoOperations.bulkOps(BulkOperations.BulkMode.UNORDERED, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME);
                }
                bulkOperations.upsert(new Query(upsertCriteria), update);
            }
        }

        if (null != bulkOperations) {
            bulkOperations.execute();
        }
    }

    public void deleteTaskMeasurement(String taskId) {
        if (StringUtils.isEmpty(taskId)) {
            return;
        }
        Query query = Query.query(Criteria.where("tags.taskId").is(taskId));
        DeleteResult result = mongoOperations.remove(query, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME);

        log.info(" taskId :{}  删除了 {} 条记录", taskId, JsonUtil.toJson(result));
    }

    public Long[] countEventByTaskRecord(String taskId, String taskRecordId) {
        Query query = new Query(Criteria.where("tags.taskId").is(taskId)
                .and("tags.taskRecordId").is(taskRecordId)
                .and("tags.type").is("task")
                .and(MeasurementEntity.FIELD_GRANULARITY).is(Granularity.GRANULARITY_MINUTE)
                .and(MeasurementEntity.FIELD_DATE).lte(new Date()));
        query.with(Sort.by(MeasurementEntity.FIELD_DATE).descending());
        MeasurementEntity measurementEntity = mongoOperations.findOne(query, MeasurementEntity.class, MeasurementEntity.COLLECTION_NAME);
        if (null == measurementEntity || null == measurementEntity.getSamples() || measurementEntity.getSamples().isEmpty()) {
            return null;
        }

        Map<String, Number> vs = measurementEntity.getSamples().get(0).getVs();
        // inputInsertTotal + inputUpdateTotal + inputDeleteTotal + inputDdlTotal + inputOthersTotal
        AtomicReference<Long> inputTotal = new AtomicReference<>(0L);
        AtomicReference<Long> outputTotal = new AtomicReference<>(0L);
        vs.remove("inputQps");
        vs.remove("outputQps");
        vs.forEach((k, v) -> {
            if (StringUtils.startsWith(k, "input")) {
                inputTotal.updateAndGet(v1 -> v1 + v.longValue());
            } else if (StringUtils.startsWith(k, "output")) {
                outputTotal.updateAndGet(v1 -> v1 + v.longValue());
            }
        });

        return new Long[]{inputTotal.get(), outputTotal.get()};
    }

    public Page<TableSyncStaticVo> querySyncStatic(TableSyncStaticDto dto, UserDetail userDetail) {
        String taskRecordId = dto.getTaskRecordId();

        Query taskQuery = new Query(Criteria.where("taskRecordId").is(taskRecordId));
        TaskDto taskDto = taskService.findOne(taskQuery, userDetail);
        boolean hasTableRenameNode = taskDto.getDag().getNodes().stream().anyMatch(n -> n instanceof TableRenameProcessNode);

        Criteria criteria = Criteria.where("tags.taskId").is(taskDto.getId().toHexString())
                .and("tags.taskRecordId").is(taskRecordId)
                .and("tags.type").is("table")
                .and(MeasurementEntity.FIELD_GRANULARITY).is(Granularity.GRANULARITY_MINUTE);

        MatchOperation match = Aggregation.match(criteria);
        GroupOperation group = Aggregation.group(MeasurementEntity.FIELD_TAGS)
                .first(MeasurementEntity.FIELD_DATE).as(MeasurementEntity.FIELD_DATE)
                .first(MeasurementEntity.FIELD_TAGS).as(MeasurementEntity.FIELD_TAGS)
                .first(MeasurementEntity.FIELD_SAMPLES).as(MeasurementEntity.FIELD_SAMPLES);

        LimitOperation limit = Aggregation.limit(dto.getSize());
        SkipOperation skip = Aggregation.skip(dto.getSize() * (dto.getPage() - 1));

        // TODO(dexter): find out a more elegant way to get the aggregate size
        // match should be at the first param, sort should be the second while group be the last
        Aggregation cntAggregation = Aggregation.newAggregation(match, group);
        AggregationResults<MeasurementEntity> results = mongoOperations.aggregate(cntAggregation, MeasurementEntity.COLLECTION_NAME, MeasurementEntity.class);
        long total = results.getMappedResults().size();
        if (total == 0) {
            return new Page<>(0, Collections.emptyList());
        }

        // get table map from task dag
        AtomicReference<Map<String, String>> tableNameMap = new AtomicReference<>();
        tableNameMap.set(new HashMap<>());
        if (hasTableRenameNode) {
            DatabaseNode targetNode = taskDto.getDag().getTargetNode().getLast();
            List<MetadataInstancesDto> metas = metadataInstancesService.findBySourceIdAndTableNameList(targetNode.getConnectionId(),
                    null, userDetail, taskDto.getId().toHexString());
            // filter by nodeId ,old data nodeId will null
            // get table origin name and target name
            tableNameMap.set(metas.stream()
                    .filter(meta -> Objects.nonNull(meta.getNodeId()) && meta.getNodeId().equals(targetNode.getId()))
                    .collect(Collectors.toMap(MetadataInstancesDto::getAncestorsName, MetadataInstancesDto::getName, (k1, k2) -> k2)));
        }

        List<TableSyncStaticVo> result = new ArrayList<>();
        SortOperation sort = Aggregation.sort(Sort.by(MeasurementEntity.FIELD_DATE).descending())
                .and(Sort.by(String.format(TAG_FORMAT, "table")).ascending());
        // match should be at the first param, sort should be the second while group be the last
        Aggregation aggregation = Aggregation.newAggregation( match, sort, group, sort, skip, limit);
        mongoOperations.aggregateStream(aggregation, MeasurementEntity.COLLECTION_NAME, MeasurementEntity.class).forEachRemaining(measurementEntity -> {
            String originTable = measurementEntity.getTags().get("table");
            AtomicReference<String> originTableName = new AtomicReference<>();
            FunctionUtils.isTureOrFalse(hasTableRenameNode).trueOrFalseHandle(
                    () -> originTableName.set(tableNameMap.get().get(originTable)),
                    () -> originTableName.set(originTable));

            List<Sample> samples = measurementEntity.getSamples();
            if (CollectionUtils.isEmpty(samples)) {
                return;
            }

            Map<String, Number> vs = samples.get(0).getVs();
            long snapshotInsertRowTotal = vs.get("snapshotInsertRowTotal").longValue();
            long snapshotRowTotal = vs.get("snapshotRowTotal").longValue();

            BigDecimal syncRate = BigDecimal.ZERO;
            if (snapshotRowTotal != 0) {
                syncRate = new BigDecimal(snapshotInsertRowTotal).divide(new BigDecimal(snapshotRowTotal), 2, RoundingMode.HALF_UP);
            } else {
                syncRate = BigDecimal.ONE;
            }

            String fullSyncStatus;
            if (syncRate.compareTo(BigDecimal.ONE) == 0) {
                fullSyncStatus = "DONE";
            } else if (syncRate.compareTo(BigDecimal.ZERO) == 0) {
                fullSyncStatus = "NOT_START";
            } else if (!TaskDto.STATUS_RUNNING.equals(taskDto.getStatus())) {
                fullSyncStatus = "NOT_START";
            } else {
                fullSyncStatus = "ING";
            }

            TableSyncStaticVo vo = new TableSyncStaticVo();
            vo.setOriginTable(originTable);
            vo.setTargetTable(originTableName.get());
            vo.setFullSyncStatus(fullSyncStatus);
            vo.setSyncRate(syncRate);

            result.add(vo);
        });

        return new Page<>(total, result);
    }
}
