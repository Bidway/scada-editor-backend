package com.example.auth.repository;

import com.example.auth.model.User;
import com.example.auth.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends PostgresTestContainerSupport {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByLogin_existingUser_returnsOptionalWithUser() {
        userRepository.save(new User("alice", "hashed_password"));

        Optional<User> result = userRepository.findByLogin("alice");

        assertThat(result).isPresent();
        assertThat(result.get().getLogin()).isEqualTo("alice");
    }

    @Test
    void findByLogin_nonExistentUser_returnsEmptyOptional() {
        Optional<User> result = userRepository.findByLogin("nobody");

        assertThat(result).isEmpty();
    }

    @Test
    void save_duplicateLogin_throwsDataIntegrityViolationException() {
        userRepository.save(new User("bob", "hash1"));
        userRepository.flush();

        assertThatThrownBy(() -> {
            userRepository.save(new User("bob", "hash2"));
            userRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
