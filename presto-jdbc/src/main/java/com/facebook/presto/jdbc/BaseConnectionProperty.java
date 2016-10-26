/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.jdbc;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;

import static java.lang.String.format;

public class BaseConnectionProperty<T> implements ConnectionProperty<T>
{
    private final String key;
    private final Optional<String> defaultValue;
    private final Predicate<Properties> isRequired;
    private final Converter<T> converter;

    protected BaseConnectionProperty(
            String key,
            Optional<String> defaultValue,
            Predicate<Properties> isRequired,
            Converter<T> converter)
    {
        this.key = key;
        this.defaultValue = defaultValue;
        this.isRequired = isRequired;
        this.converter = converter;
    }

    protected BaseConnectionProperty(
            String key,
            Predicate<Properties> required,
            Converter<T> converter)
    {
        this(key, Optional.empty(), required, converter);
    }

    @Override
    public String getKey()
    {
        return key;
    }

    @Override
    public Optional<String> getDefault()
    {
        return defaultValue;
    }

    @Override
    public DriverPropertyInfo getDriverPropertyInfo(Properties mergedProperties)
    {
        String currentValue = mergedProperties.getProperty(key);
        DriverPropertyInfo result = new DriverPropertyInfo(key, currentValue);
        result.required = isRequired.test(mergedProperties);
        return result;
    }

    @Override
    public boolean isRequired(Properties properties)
    {
        return isRequired.test(properties);
    }

    @Override
    public Optional<T> getValue(Properties properties)
            throws SQLException
    {
        String value = properties.getProperty(key);
        if (value == null) {
            if (isRequired(properties)) {
                throw new SQLException(format("Connection property/URL parameter %s is required", key));
            }
            else {
                return Optional.empty();
            }
        }

        try {
            return Optional.of(converter.convert(value));
        }
        catch (Exception e) {
            throw new SQLException(format("Failed to convert value %s for key %s", value, key), e);
        }
    }

    @Override
    public void validate(Properties properties)
            throws SQLException
    {
        getValue(properties);
    }

    protected static final Predicate<Properties> REQUIRED = (properties) -> true;
    protected static final Predicate<Properties> NOT_REQUIRED = (properties) -> false;

    private interface Converter<T>
    {
        T convert(String value)
                throws Exception;
    }

    protected static final Converter<String> STRING_CONVERTER = (value) -> value;
    protected static final Converter<Integer> INTEGER_CONVERTER = (value) -> Integer.parseInt(value);
    protected static <T> Predicate<Properties> dependsKeyValue(ConnectionProperty property, T value)
    {
        return (properties -> properties.get(property.getKey()).equals(value.toString()));
    }
}