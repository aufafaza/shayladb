package mdbms.api;

import mdbms.models.Statistic;

public interface IStatisticsProvider{
    Statistic getStatistics(String table);
}